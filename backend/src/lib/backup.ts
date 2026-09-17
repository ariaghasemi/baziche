// D1 -> R2 disaster-recovery backup (runs on cron, see wrangler [triggers]).
// One gzipped JSON document per run: {v, at, tables: {name: rows[]}}.
// Includes credential hashes (needed for restore) — the bucket is private and
// R2 encrypts at rest; never make R2_BUILDS public. Restore: docs/RUNBOOK.md.

const AUTH_TABLES = ['users', 'refresh_tokens', 'plans', 'subscriptions', 'entitlements', 'purchases', 'admins', 'idempotency_keys', 'ai_usage'] as const;
const DATA_TABLES = ['projects', 'project_revisions', 'assets', 'builds', 'signing_keys', 'storage_shards', 'agents', 'feature_flags'] as const;
const AUDIT_CAP = 20000;

async function dumpAll(db: D1Database, table: string, cap?: number): Promise<Record<string, unknown>[]> {
  const sql = cap ? `SELECT * FROM ${table} ORDER BY rowid DESC LIMIT ${cap}` : `SELECT * FROM ${table}`;
  const r = await db.prepare(sql).all<Record<string, unknown>>();
  return r.results ?? [];
}

export interface BackupSummary {
  key: string;
  at: number;
  tables: number;
  rows: number;
}

export async function runBackup(env: { DB_AUTH: D1Database; DB_DATA: D1Database; R2_BUILDS: R2Bucket }): Promise<BackupSummary> {
  const at = Math.floor(Date.now() / 1000);
  const tables: Record<string, Record<string, unknown>[]> = {};
  for (const t of AUTH_TABLES) tables[`auth.${t}`] = await dumpAll(env.DB_AUTH, t);
  tables['auth.audit_logs'] = await dumpAll(env.DB_AUTH, 'audit_logs', AUDIT_CAP);
  for (const t of DATA_TABLES) tables[`data.${t}`] = await dumpAll(env.DB_DATA, t);
  const rows = Object.values(tables).reduce((n, r) => n + r.length, 0);

  const doc = JSON.stringify({ v: 1, at, tables });
  const gz = await new Response(new Blob([doc]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer();
  const day = new Date(at * 1000).toISOString().slice(0, 10);
  const key = `backups/${day}/d1-${at}.json.gz`;
  await env.R2_BUILDS.put(key, gz, { httpMetadata: { contentType: 'application/gzip' } });
  await env.R2_BUILDS.put('backups/latest.json', JSON.stringify({ key, at, tables: Object.keys(tables).length, rows }));
  return { key, at, tables: Object.keys(tables).length, rows };
}
