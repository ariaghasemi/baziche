import { Hono } from 'hono';
import type { Env } from '../index';
import { nowSec } from '../lib/errors';
import { parse, zId } from '../lib/validate';
import { requireAuth, requireAdmin } from '../middleware/auth';
import { audit } from '../lib/audit';

export const adminRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();
adminRoutes.use('*', requireAuth);
adminRoutes.use('*', requireAdmin);

async function count(db: D1Database, sql: string, ...params: unknown[]): Promise<number> {
  const r = await db.prepare(sql).bind(...(params as never[])).first<{ n: number }>();
  return r?.n ?? 0;
}

adminRoutes.get('/stats', async (c) => {
  const db = c.env.DB;
  const [users, projects, buildsQueued, buildsDone, subs] = await Promise.all([
    count(db, 'SELECT COUNT(*) AS n FROM users'),
    count(db, "SELECT COUNT(*) AS n FROM projects WHERE status = 'active'"),
    count(db, "SELECT COUNT(*) AS n FROM builds WHERE status IN ('QUEUED','PREPARING','BUILDING','SIGNING','UPLOADING')"),
    count(db, "SELECT COUNT(*) AS n FROM builds WHERE status = 'COMPLETED'"),
    count(db, "SELECT COUNT(*) AS n FROM subscriptions WHERE status = 'active'"),
  ]);
  return c.json({ success: true, stats: { users, projects, buildsQueued, buildsDone, activeSubscriptions: subs } });
});

adminRoutes.get('/users', async (c) => {
  const limit = Math.min(100, Math.max(1, parseInt(c.req.query('limit') || '50', 10) || 50));
  const rows = await c.env.DB.prepare(
    `SELECT id, phone, username, status, free_build_state AS freeBuild, created_at AS createdAt, last_login AS lastLogin
     FROM users ORDER BY created_at DESC LIMIT ?`,
  )
    .bind(limit)
    .all();
  return c.json({ success: true, users: rows.results ?? [] });
});

adminRoutes.post('/users/:id/suspend', async (c) => {
  const id = parse(zId, c.req.param('id'));
  await c.env.DB.prepare("UPDATE users SET status = 'suspended', updated_at = ? WHERE id = ?").bind(nowSec(), id).run();
  await c.env.DB.prepare('UPDATE refresh_tokens SET revoked = 1 WHERE user_id = ?').bind(id).run();
  await audit(c.env.DB, 'admin.suspend', c.get('userId'), { target: id });
  return c.json({ success: true });
});

adminRoutes.post('/users/:id/restore', async (c) => {
  const id = parse(zId, c.req.param('id'));
  await c.env.DB.prepare("UPDATE users SET status = 'active', updated_at = ? WHERE id = ?").bind(nowSec(), id).run();
  await audit(c.env.DB, 'admin.restore', c.get('userId'), { target: id });
  return c.json({ success: true });
});

adminRoutes.post('/users/:id/reset-free-build', async (c) => {
  const id = parse(zId, c.req.param('id'));
  await c.env.DB.prepare("UPDATE users SET free_build_state = 'AVAILABLE', free_build_id = NULL, updated_at = ? WHERE id = ?")
    .bind(nowSec(), id)
    .run();
  await audit(c.env.DB, 'admin.reset_free_build', c.get('userId'), { target: id });
  return c.json({ success: true });
});

adminRoutes.get('/audit', async (c) => {
  const limit = Math.min(200, Math.max(1, parseInt(c.req.query('limit') || '100', 10) || 100));
  const rows = await c.env.DB.prepare('SELECT id, at, user_id AS userId, action, meta FROM audit_logs ORDER BY id DESC LIMIT ?')
    .bind(limit)
    .all();
  return c.json({ success: true, logs: rows.results ?? [] });
});

// Safety: unknown admin paths -> 404 (no information leak about future routes).
adminRoutes.notFound((c) => c.json({ success: false, error: { code: 'NOT_FOUND', message: 'Not found' } }, 404));
