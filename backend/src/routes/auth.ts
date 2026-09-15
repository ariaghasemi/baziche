import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, newId, nowSec } from '../lib/errors';
import { parse, zHex, zUsername } from '../lib/validate';
import { normalizePhone } from '../lib/phone';
import { ALGORITHM, MAX_ITERATIONS, MIN_ITERATIONS, serverPepperHasher, sha256Hex } from '../lib/password';
import { newOpaqueToken, signAccessToken } from '../lib/jwt';
import { checkLoginRateLimit, pruneLoginAttempts } from '../lib/ratelimit';
import { audit } from '../lib/audit';
import { getEntitlement } from '../lib/entitlements';
import { requireAuth } from '../middleware/auth';

export const authRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();

// Dummy challenge values for unknown phones (anti-enumeration: same shape, random salt).
async function dummyChallenge() {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return {
    salt: [...b].map((x) => x.toString(16).padStart(2, '0')).join(''),
    iterations: MIN_ITERATIONS,
    algorithm: ALGORITHM,
  };
}

authRoutes.post('/challenge', async (c) => {
  const body = parse(z.object({ phone: z.string().min(1).max(32) }), await c.req.json());
  const phone = normalizePhone(body.phone);
  if (!phone) throw err('INVALID_PHONE', 'Invalid phone number', 400);
  const row = await c.env.DB_AUTH.prepare('SELECT salt, iterations FROM users WHERE phone = ?')
    .bind(phone)
    .first<{ salt: string; iterations: number }>();
  if (!row) return c.json({ success: true, ...(await dummyChallenge()) });
  return c.json({ success: true, salt: row.salt, iterations: row.iterations, algorithm: ALGORITHM });
});

const registerSchema = z.object({
  phone: z.string().min(1).max(32),
  username: zUsername,
  salt: zHex(32, 128),
  clientHash: zHex(128, 128),
  iterations: z.number().int().min(MIN_ITERATIONS).max(MAX_ITERATIONS).default(MIN_ITERATIONS),
  device: z.string().max(128).optional(),
});

authRoutes.post('/register', async (c) => {
  const b = parse(registerSchema, await c.req.json());
  const phone = normalizePhone(b.phone);
  if (!phone) throw err('INVALID_PHONE', 'Invalid phone number', 400);
  const username = b.username;
  const usernameLower = username.toLowerCase();

  const exists = await c.env.DB_AUTH.prepare('SELECT phone, username_lower AS u FROM users WHERE phone = ? OR username_lower = ?')
    .bind(phone, usernameLower)
    .first<{ phone: string; u: string }>();
  if (exists) {
    if (exists.phone === phone) throw err('PHONE_TAKEN', 'Phone already registered', 409);
    throw err('USERNAME_TAKEN', 'Username already taken', 409);
  }

  const serverHash = await serverPepperHasher.finalize(b.clientHash, c.env.PASSWORD_PEPPER);
  const id = newId('usr');
  const now = nowSec();
  await c.env.DB_AUTH.prepare(
    `INSERT INTO users (id, phone, username, username_lower, salt, iterations, server_hash, status, free_build_state, created_at, updated_at, last_login)
     VALUES (?, ?, ?, ?, ?, ?, ?, 'active', 'AVAILABLE', ?, ?, ?)`,
  )
    .bind(id, phone, username, usernameLower, b.salt.toLowerCase(), b.iterations, serverHash, now, now, now)
    .run();

  const session = await issueSession(c.env, id, b.device ?? null);
  await audit(c.env.DB_AUTH, 'user.register', id, { phone: phone.slice(0, 6) + '***' });
  return c.json({
    success: true,
    user: { id, phone, username, status: 'active' },
    ...session,
  });
});

const loginSchema = z.object({
  phone: z.string().min(1).max(32),
  clientHash: zHex(128, 128),
  device: z.string().max(128).optional(),
});

authRoutes.post('/login', async (c) => {
  const b = parse(loginSchema, await c.req.json());
  const phone = normalizePhone(b.phone);
  if (!phone) throw err('INVALID_PHONE', 'Invalid phone number', 400);
  const ip = c.req.header('cf-connecting-ip') ?? c.req.header('x-forwarded-for') ?? 'unknown';

  const rl = await checkLoginRateLimit(c.env.DB_AUTH, phone, String(ip));
  if (!rl.allowed) {
    c.header('Retry-After', String(rl.retryAfterSec));
    throw err('RATE_LIMITED', 'Too many attempts, try later', 429, { retryAfterSec: rl.retryAfterSec });
  }

  const row = await c.env.DB_AUTH.prepare(
    'SELECT id, username, status, server_hash AS h FROM users WHERE phone = ?',
  )
    .bind(phone)
    .first<{ id: string; username: string; status: string; h: string }>();

  // Always run the hash to keep timing uniform, then fail generically.
  const ok = row
    ? await serverPepperHasher.verify(b.clientHash, c.env.PASSWORD_PEPPER, row.h)
    : await serverPepperHasher.verify(b.clientHash, c.env.PASSWORD_PEPPER, '0'.repeat(64)).then(() => false);

  if (!row || !ok) throw err('INVALID_CREDENTIALS', 'Invalid credentials', 401);
  if (row.status !== 'active') throw err('ACCOUNT_SUSPENDED', 'Account suspended', 403);

  const now = nowSec();
  await c.env.DB_AUTH.prepare('UPDATE users SET last_login = ?, updated_at = ? WHERE id = ?').bind(now, now, row.id).run();
  // Successful login resets the brute-force window for this phone.
  await c.env.DB_AUTH.prepare('DELETE FROM login_attempts WHERE phone = ?').bind(phone).run();
  const session = await issueSession(c.env, row.id, b.device ?? null);
  await audit(c.env.DB_AUTH, 'user.login', row.id, {});
  void pruneLoginAttempts(c.env.DB_AUTH).catch(() => {});
  return c.json({ success: true, user: { id: row.id, phone, username: row.username, status: row.status }, ...session });
});

authRoutes.post('/refresh', async (c) => {
  const b = parse(z.object({ refreshToken: z.string().min(16).max(256) }), await c.req.json());
  const hash = await sha256Hex(b.refreshToken);
  const row = await c.env.DB_AUTH.prepare(
    'SELECT id, user_id AS uid, expires_at AS exp, revoked FROM refresh_tokens WHERE token_hash = ?',
  )
    .bind(hash)
    .first<{ id: string; uid: string; exp: number; revoked: number }>();
  if (!row || row.revoked || row.exp <= nowSec()) throw err('REFRESH_INVALID', 'Invalid refresh token', 401);

  // Rotation: revoke old, issue new.
  await c.env.DB_AUTH.prepare('UPDATE refresh_tokens SET revoked = 1 WHERE id = ?').bind(row.id).run();
  const session = await issueSession(c.env, row.uid, null);
  return c.json({ success: true, ...session });
});

authRoutes.post('/logout', async (c) => {
  const b = parse(z.object({ refreshToken: z.string().min(16).max(256) }), await c.req.json());
  const hash = await sha256Hex(b.refreshToken);
  await c.env.DB_AUTH.prepare('UPDATE refresh_tokens SET revoked = 1 WHERE token_hash = ?').bind(hash).run();
  return c.json({ success: true });
});

async function issueSession(env: Env, userId: string, device: string | null) {
  const accessTtl = parseInt(env.ACCESS_TOKEN_TTL_SEC || '900', 10);
  const refreshTtl = parseInt(env.REFRESH_TOKEN_TTL_SEC || '2592000', 10);
  const accessToken = await signAccessToken(userId, env.JWT_SECRET, accessTtl);
  const refreshToken = newOpaqueToken(32);
  const hash = await sha256Hex(refreshToken);
  const now = nowSec();
  await env.DB_AUTH.prepare(
    'INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked, device, created_at) VALUES (?, ?, ?, ?, 0, ?, ?)',
  )
    .bind(newId('rtk'), userId, hash, now + refreshTtl, device, now)
    .run();
  return { accessToken, refreshToken, expiresIn: accessTtl };
}

// Shared /me payload (mounted at both /me and /auth/me, auth-guarded).
async function mePayload(authDb: D1Database, dataDb: D1Database, userId: string) {
  const u = await authDb.prepare(
    'SELECT id, phone, username, status, free_build_state AS fbs, created_at AS ca FROM users WHERE id = ?',
  )
    .bind(userId)
    .first<{ id: string; phone: string; username: string; status: string; fbs: string; ca: number }>();
  if (!u) throw err('TOKEN_INVALID', 'Unknown user', 401);
  const entitlement = await getEntitlement(authDb, userId);
  const pc = await dataDb.prepare("SELECT COUNT(*) AS n FROM projects WHERE user_id = ? AND status = 'active'")
    .bind(userId)
    .first<{ n: number }>();
  return {
    user: { id: u.id, phone: u.phone, username: u.username, status: u.status, createdAt: u.ca, projects: pc?.n ?? 0 },
    entitlement,
  };
}

// GET /me at API root (OpenAPI) + alias at /auth/me. Both auth-guarded.
export const meRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();
// NOTE: no .use('*') here — this router is mounted at /api/v1 root and a wildcard
// middleware would guard every v1 route. Guard per-route instead.
meRoutes.get('/me', requireAuth, async (c) =>
  c.json({ success: true, ...(await mePayload(c.env.DB_AUTH, c.env.DB_DATA, c.get('userId'))) }),
);

authRoutes.get('/me', requireAuth, async (c) =>
  c.json({ success: true, ...(await mePayload(c.env.DB_AUTH, c.env.DB_DATA, c.get('userId'))) }),
);
