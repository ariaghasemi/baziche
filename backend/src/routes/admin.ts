import { Hono } from 'hono';
import type { Env } from '../index';
import { nowSec } from '../lib/errors';
import { parse, zId } from '../lib/validate';
import { requireAuth, requireAdmin } from '../middleware/auth';
import { verifyAccessToken } from '../lib/jwt';
import { audit } from '../lib/audit';

const esc = (v: unknown) =>
  String(v ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

export const adminRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();
adminRoutes.use('*', requireAuth);
adminRoutes.use('*', requireAdmin);

async function count(db: D1Database, sql: string, ...params: unknown[]): Promise<number> {
  const r = await db.prepare(sql).bind(...(params as never[])).first<{ n: number }>();
  return r?.n ?? 0;
}

adminRoutes.get('/stats', async (c) => {
  const [users, projects, buildsQueued, buildsDone, subs] = await Promise.all([
    count(c.env.DB_AUTH, 'SELECT COUNT(*) AS n FROM users'),
    count(c.env.DB_DATA, "SELECT COUNT(*) AS n FROM projects WHERE status = 'active'"),
    count(c.env.DB_DATA, "SELECT COUNT(*) AS n FROM builds WHERE status IN ('QUEUED','PREPARING','BUILDING','SIGNING','UPLOADING')"),
    count(c.env.DB_DATA, "SELECT COUNT(*) AS n FROM builds WHERE status = 'COMPLETED'"),
    count(c.env.DB_AUTH, "SELECT COUNT(*) AS n FROM subscriptions WHERE status = 'active'"),
  ]);
  return c.json({ success: true, stats: { users, projects, buildsQueued, buildsDone, activeSubscriptions: subs } });
});

adminRoutes.get('/users', async (c) => {
  const limit = Math.min(100, Math.max(1, parseInt(c.req.query('limit') || '50', 10) || 50));
  const rows = await c.env.DB_AUTH.prepare(
    `SELECT id, phone, username, status, free_build_state AS freeBuild, created_at AS createdAt, last_login AS lastLogin
     FROM users ORDER BY created_at DESC LIMIT ?`,
  )
    .bind(limit)
    .all();
  return c.json({ success: true, users: rows.results ?? [] });
});

adminRoutes.post('/users/:id/suspend', async (c) => {
  const id = parse(zId, c.req.param('id'));
  await c.env.DB_AUTH.prepare("UPDATE users SET status = 'suspended', updated_at = ? WHERE id = ?").bind(nowSec(), id).run();
  await c.env.DB_AUTH.prepare('UPDATE refresh_tokens SET revoked = 1 WHERE user_id = ?').bind(id).run();
  await audit(c.env.DB_AUTH, 'admin.suspend', c.get('userId'), { target: id });
  return c.json({ success: true });
});

adminRoutes.post('/users/:id/restore', async (c) => {
  const id = parse(zId, c.req.param('id'));
  await c.env.DB_AUTH.prepare("UPDATE users SET status = 'active', updated_at = ? WHERE id = ?").bind(nowSec(), id).run();
  await audit(c.env.DB_AUTH, 'admin.restore', c.get('userId'), { target: id });
  return c.json({ success: true });
});

adminRoutes.post('/users/:id/reset-free-build', async (c) => {
  const id = parse(zId, c.req.param('id'));
  await c.env.DB_AUTH.prepare("UPDATE users SET free_build_state = 'AVAILABLE', free_build_id = NULL, updated_at = ? WHERE id = ?")
    .bind(nowSec(), id)
    .run();
  await audit(c.env.DB_AUTH, 'admin.reset_free_build', c.get('userId'), { target: id });
  return c.json({ success: true });
});

adminRoutes.get('/audit', async (c) => {
  const limit = Math.min(200, Math.max(1, parseInt(c.req.query('limit') || '100', 10) || 100));
  const rows = await c.env.DB_AUTH.prepare('SELECT id, at, user_id AS userId, action, meta FROM audit_logs ORDER BY id DESC LIMIT ?')
    .bind(limit)
    .all();
  return c.json({ success: true, logs: rows.results ?? [] });
});

// Server-rendered admin console (no build step, no JS framework).
// Separate sub-app WITHOUT the Bearer middleware: browsers authenticate with
// ?token=<admin access token> (TLS-only in production; token never logged).
// Mounted at /admin (NOT /api/v1/admin) so the JSON middleware never intercepts.
export const adminUiRoutes = new Hono<{ Bindings: Env }>();
adminUiRoutes.get('/ui', async (c) => {
  const token = c.req.query('token') ?? '';
  let adminId = '';
  try {
    adminId = await verifyAccessToken(token, c.env.JWT_SECRET, c.env.JWT_SECRET_PREV || undefined);
  } catch {
    return c.text('unauthorized: bad token', 401);
  }
  const a = await c.env.DB_AUTH.prepare('SELECT role FROM admins WHERE user_id = ?').bind(adminId).first<{ role: string }>();
  if (!a) return c.text('forbidden: not an admin', 403);

  const [users, builds, subs] = await Promise.all([
    c.env.DB_AUTH.prepare(
      `SELECT id, phone, username, status, free_build_state AS freeBuild, created_at AS createdAt FROM users ORDER BY created_at DESC LIMIT 20`,
    ).all(),
    c.env.DB_DATA.prepare(
      `SELECT id, user_id AS userId, project_id AS projectId, rev, status, spent, queued_at AS queuedAt, finished_at AS finishedAt
       FROM builds ORDER BY queued_at DESC LIMIT 20`,
    ).all(),
    c.env.DB_AUTH.prepare(`SELECT COUNT(*) AS n FROM subscriptions WHERE status = 'active'`).first<{ n: number }>(),
  ]);
  const urows = (users.results ?? []) as Record<string, unknown>[];
  const brows = (builds.results ?? []) as Record<string, unknown>[];
  const q = (s: string) => encodeURIComponent(s);

  const html = `<!DOCTYPE html><html lang="fa" dir="rtl"><head><meta charset="utf-8">
<title>Baziche Admin</title>
<style>body{font-family:sans-serif;margin:24px;background:#f6f6f6;color:#222}h1{font-size:22px}
table{border-collapse:collapse;background:#fff;margin:12px 0;width:100%;font-size:13px}
td,th{border:1px solid #ddd;padding:6px 8px;text-align:right}th{background:#eee}
.badge{display:inline-block;padding:1px 8px;border-radius:10px;background:#e0e0e0}
.ok{background:#c8e6c9}.warn{background:#ffe082}.bad{background:#ffcdd2}
button{margin:0 2px;cursor:pointer}code{font-size:12px}</style></head><body>
<h1>🎮 مدیریت بازیچه</h1>
<p>اشتراک‌های فعال: <b>${subs?.n ?? 0}</b> | کاربر (۲۰ تای آخر) | بیلد (۲۰ تای آخر)</p>
<h2>کاربران</h2>
<table><tr><th>id</th><th>phone</th><th>username</th><th>وضعیت</th><th>free build</th><th>اقدام</th></tr>
${urows.map((u) => `<tr><td><code>${esc(u.id)}</code></td><td>${esc(u.phone)}</td><td>${esc(u.username)}</td>
<td><span class="badge ${u.status === 'active' ? 'ok' : 'bad'}">${esc(u.status)}</span></td>
<td>${esc(u.freeBuild)}</td>
<td><button onclick="act('${esc(u.id)}','suspend')">تعلیق</button><button onclick="act('${esc(u.id)}','restore')">رفع</button><button onclick="act('${esc(u.id)}','reset-free-build')">ریست free</button></td></tr>`).join('')}
</table>
<h2>بیلدها</h2>
<table><tr><th>id</th><th>user</th><th>project</th><th>rev</th><th>وضعیت</th><th>spent</th><th>queued</th></tr>
${brows.map((b) => `<tr><td><code>${esc(String(b.id).slice(0, 18))}…</code></td><td><code>${esc(String(b.userId).slice(0, 12))}…</code></td>
<td><code>${esc(String(b.projectId).slice(0, 12))}…</code></td><td>${esc(b.rev)}</td>
<td><span class="badge ${b.status === 'COMPLETED' ? 'ok' : b.status === 'FAILED' ? 'bad' : 'warn'}">${esc(b.status)}</span></td>
<td>${esc(b.spent ?? 'sub')}</td><td>${esc(b.queuedAt)}</td></tr>`).join('')}
</table>
<script>
const T=${JSON.stringify(token)};
async function act(id, op){
  const r = await fetch('/api/v1/admin/users/'+encodeURIComponent(id)+'/'+op, {method:'POST', headers:{authorization:'Bearer '+T}});
  alert(r.ok ? 'انجام شد' : 'خطا: ' + r.status);
  if (r.ok) location.reload();
}
</script>
</body></html>`;
  void q;
  return c.html(html);
});

// Safety: unknown admin paths -> 404 (no information leak about future routes).
adminRoutes.notFound((c) => c.json({ success: false, error: { code: 'NOT_FOUND', message: 'Not found' } }, 404));
