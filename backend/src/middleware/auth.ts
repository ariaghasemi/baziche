import { createMiddleware } from 'hono/factory';
import type { Env } from '../index';
import { err } from '../lib/errors';
import { verifyAccessToken } from '../lib/jwt';

export const requireAuth = createMiddleware<{ Bindings: Env; Variables: { userId: string } }>(
  async (c, next) => {
    const h = c.req.header('authorization') ?? '';
    const m = /^Bearer (.+)$/.exec(h);
    if (!m) throw err('TOKEN_INVALID', 'Missing bearer token', 401);
    try {
      const sub = await verifyAccessToken(m[1], c.env.JWT_SECRET, c.env.JWT_SECRET_PREV || undefined);
      const u = await c.env.DB_AUTH.prepare('SELECT status FROM users WHERE id = ?')
        .bind(sub)
        .first<{ status: string }>();
      if (!u) throw err('TOKEN_INVALID', 'Unknown user', 401);
      if (u.status !== 'active') throw err('ACCOUNT_SUSPENDED', 'Account suspended', 403);
      c.set('userId', sub);
    } catch (e) {
      if (e instanceof Error && (e as { code?: string }).code) throw e;
      const msg = e instanceof Error ? e.message : '';
      if (/exp|expir/i.test(msg)) throw err('TOKEN_EXPIRED', 'Token expired', 401);
      throw err('TOKEN_INVALID', 'Invalid token', 401);
    }
    await next();
  },
);

export const requireAdmin = createMiddleware<{ Bindings: Env; Variables: { userId: string } }>(
  async (c, next) => {
    // requireAuth must run first (mounted before in admin routes).
    const userId = c.get('userId');
    if (!userId) throw err('TOKEN_INVALID', 'Missing auth', 401);
    const a = await c.env.DB_AUTH.prepare('SELECT role FROM admins WHERE user_id = ?')
      .bind(userId)
      .first<{ role: string }>();
    if (!a) throw err('FORBIDDEN', 'Admin required', 403);
    await next();
  },
);
