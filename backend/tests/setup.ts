// Test harness: REAL migration SQL executed on sql.js (SQLite), D1-compatible adapters,
// in-memory R2 fakes, and Hono app.request() — no mocks of app logic.
import { readFileSync, readdirSync } from 'node:fs';
import initSqlJs, { type Database } from 'sql.js';
import { createApp, type Env } from '../src/index';

// Apply EVERY migration in order (001, 002, ...) so tests always match production D1.
function migrateAll(db: Database, dirUrl: URL) {
  const files = readdirSync(dirUrl).filter((f) => f.endsWith('.sql')).sort();
  for (const f of files) db.exec(readFileSync(new URL(f, dirUrl), 'utf8'));
}

let SQL: Awaited<ReturnType<typeof initSqlJs>> | null = null;

async function sqlLib() {
  if (!SQL) SQL = await initSqlJs();
  return SQL;
}

function makeD1(db: Database): D1Database {
  const runStmt = (sql: string, params: unknown[]) => {
    const stmt = db.prepare(sql);
    try {
      stmt.bind(params as never);
      const rows: Record<string, unknown>[] = [];
      while (stmt.step()) rows.push(stmt.getAsObject() as Record<string, unknown>);
      return rows;
    } finally {
      stmt.free();
    }
  };
  const execStmt = (sql: string, params: unknown[]): number => {
    db.run(sql, params as never);
    return db.getRowsModified();
  };
  return {
    prepare: (sql: string) => {
      const bound = (params: unknown[]) => ({
        all: async <T,>() => ({ results: runStmt(sql, params) as T[] }),
        first: async <T,>() => {
          const r = runStmt(sql, params);
          return (r[0] ?? null) as T | null;
        },
        run: async () => ({ meta: { changes: execStmt(sql, params) } }),
      });
      return {
        bind: (...params: unknown[]) => bound(params),
        // D1 also allows calling without bind() for param-less statements.
        all: async <T,>() => ({ results: runStmt(sql, []) as T[] }),
        first: async <T,>() => {
          const r = runStmt(sql, []);
          return (r[0] ?? null) as T | null;
        },
        run: async () => ({ meta: { changes: execStmt(sql, []) } }),
      };
    },
  } as unknown as D1Database;
}

function makeR2() {
  const store = new Map<string, Uint8Array>();
  const toBytes = async (v: unknown): Promise<Uint8Array> => {
    if (typeof v === 'string') return new TextEncoder().encode(v);
    if (v instanceof Uint8Array) return v;
    if (v instanceof ArrayBuffer) return new Uint8Array(v);
    return new Uint8Array(await (v as Blob).arrayBuffer());
  };
  return {
    __store: store,
    put: async (key: string, value: unknown) => {
      store.set(key, await toBytes(value));
    },
    get: async (key: string, options?: { range?: { offset?: number; length?: number } }) => {
      const b = store.get(key);
      if (!b) return null;
      const body = options?.range
        ? b.slice(options.range.offset ?? 0, (options.range.offset ?? 0) + (options.range.length ?? b.length))
        : b;
      return {
        text: async () => new TextDecoder().decode(body),
        arrayBuffer: async () => body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) as ArrayBuffer,
      };
    },
    head: async (key: string) => {
      const b = store.get(key);
      return b ? { size: b.length } : null;
    },
    delete: async (key: string) => {
      store.delete(key);
    },
  };
}

export interface TestCtx {
  env: Env;
  app: ReturnType<typeof createApp>;
  dbAuth: Database;
  dbData: Database;
  r2projects: ReturnType<typeof makeR2>;
  r2assets: ReturnType<typeof makeR2>;
  r2builds: ReturnType<typeof makeR2>;
}

export async function makeCtx(): Promise<TestCtx> {
  const lib = await sqlLib();
  const dbAuth = new lib.Database();
  const dbData = new lib.Database();
  migrateAll(dbAuth, new URL('../migrations/auth/', import.meta.url));
  migrateAll(dbData, new URL('../migrations/data/', import.meta.url));
  const r2projects = makeR2();
  const r2assets = makeR2();
  const r2builds = makeR2();
  const env = {
    DB_AUTH: makeD1(dbAuth),
    DB_DATA: makeD1(dbData),
    R2_PROJECTS: r2projects,
    R2_ASSETS: r2assets,
    R2_BUILDS: r2builds,
    ENVIRONMENT: 'test',
    ACCESS_TOKEN_TTL_SEC: '900',
    REFRESH_TOKEN_TTL_SEC: '2592000',
    REGISTRIES_VERSION: '1',
    DEFAULT_TARGET_API: '36',
    DEFAULT_MIN_API: '26',
    MAX_PROJECT_JSON_BYTES: '5242880',
    JWT_SECRET: 'test-jwt-secret-that-is-long-enough-0123456789',
    PASSWORD_PEPPER: 'ab'.repeat(32),
    R2_ACCOUNT_ID: 'testaccount',
    R2_ACCESS_KEY_ID: 'testkey',
    R2_SECRET_ACCESS_KEY: 'testsecret',
  } as unknown as Env;
  return { env, app: createApp(), dbAuth, dbData, r2projects, r2assets, r2builds };
}

// --- client-side crypto simulation (mirrors Android PasswordStretcher, WebCrypto only) ---
export async function clientStretch(password: string, saltHex: string, iterations: number): Promise<string> {
  const salt = new Uint8Array(saltHex.match(/.{2}/g)!.map((b) => parseInt(b, 16)));
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-512', salt, iterations }, key, 512);
  return [...new Uint8Array(bits)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

export function randomSaltHex(bytes = 16): string {
  const b = new Uint8Array(bytes);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}

export async function req(
  ctx: TestCtx,
  path: string,
  init: { method?: string; token?: string; body?: unknown } = {},
) {
  return ctx.app.request(
    path,
    {
      method: init.method ?? 'GET',
      headers: {
        'content-type': 'application/json',
        ...(init.token ? { authorization: `Bearer ${init.token}` } : {}),
      },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    },
    ctx.env,
  );
}

export async function registerUser(
  ctx: TestCtx,
  phone = '09123456789',
  username = 'testuser',
  password = 'StrongPass123',
) {
  const ch = await req(ctx, '/api/v1/auth/challenge', { method: 'POST', body: { phone } });
  void ch;
  const salt = randomSaltHex();
  const clientHash = await clientStretch(password, salt, 100000);
  const res = await req(ctx, '/api/v1/auth/register', {
    method: 'POST',
    body: { phone, username, salt, clientHash, iterations: 100000, device: 'vitest' },
  });
  const json = (await res.json()) as Record<string, never>;
  if (!json.success) throw new Error(`register failed: ${JSON.stringify(json)}`);
  return json as unknown as { success: true; user: { id: string; phone: string; username: string }; accessToken: string; refreshToken: string };
}

export async function createProject(ctx: TestCtx, token: string, name = 'P', gameType = 'quiz'): Promise<string> {
  const res = await req(ctx, '/api/v1/projects', { method: 'POST', token, body: { name, gameType } });
  const j = (await res.json()) as { project: { id: string } };
  return j.project.id;
}
