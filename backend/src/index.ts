import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { ApiError } from './lib/errors';
import { authRoutes, meRoutes } from './routes/auth';
import { projectRoutes } from './routes/projects';
import { assetRoutes } from './routes/assets';
import { metaRoutes } from './routes/meta';
import { adminRoutes, adminUiRoutes } from './routes/admin';
import { runBackup } from './lib/backup';
import { buildRoutes } from './routes/builds';
import { billingRoutes } from './routes/billing';
import { aiRoutes } from './routes/ai';

export interface Env {
  DB_AUTH: D1Database; // identity, sessions, billing, admin, audit
  DB_DATA: D1Database; // projects, revisions, assets, builds
  R2_PROJECTS: R2Bucket;
  R2_ASSETS: R2Bucket;
  R2_BUILDS: R2Bucket;
  // vars (non-secret)
  ENVIRONMENT: string;
  ACCESS_TOKEN_TTL_SEC: string;
  REFRESH_TOKEN_TTL_SEC: string;
  REGISTRIES_VERSION: string;
  DEFAULT_TARGET_API: string;
  DEFAULT_MIN_API: string;
  MAX_PROJECT_JSON_BYTES: string;
  // secrets
  JWT_SECRET: string;
  JWT_SECRET_PREV?: string;
  PASSWORD_PEPPER: string;
  R2_ACCOUNT_ID: string;
  R2_ACCESS_KEY_ID: string;
  R2_SECRET_ACCESS_KEY: string;
  // Phase 5: GitHub dispatch (all optional — without them builds stay QUEUED)
  GITHUB_DISPATCH_TOKEN?: string;
  GITHUB_REPO?: string;
  GAME_BUILD_REF?: string;
  // Phase 6: Myket billing (both optional — without them /verify answers 501)
  MYKET_ACCESS_TOKEN?: string;
  MYKET_PACKAGE?: string;
  // Phase 9: generic OpenAI-compatible AI (all optional — without a key /expand answers 501)
  AI_API_KEY?: string;
  AI_BASE_URL?: string;
  AI_MODEL?: string;
}

export function createApp() {
  const app = new Hono<{ Bindings: Env }>();

  app.use('*', cors({ origin: '*', allowMethods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS'] }));

  app.get('/', (c) => c.json({ success: true, service: 'baziche-api', version: 'v1', env: c.env.ENVIRONMENT }));
  app.get('/health', (c) => c.json({ success: true, status: 'ok' }));

  app.route('/api/v1/auth', authRoutes);
  app.route('/api/v1', meRoutes); // GET /api/v1/me
  app.route('/api/v1/projects', projectRoutes);
  app.route('/api/v1/assets', assetRoutes);
  app.route('/api/v1/meta', metaRoutes);
  app.route('/api/v1/admin', adminRoutes);
  app.route('/admin', adminUiRoutes); // server-rendered console: GET /admin/ui?token=...
  app.route('/api/v1/builds', buildRoutes);
  app.route('/api/v1/billing', billingRoutes);
  app.route('/api/v1/ai', aiRoutes);

  app.notFound((c) => c.json({ success: false, error: { code: 'NOT_FOUND', message: 'Not found' } }, 404));

  app.onError((e, c) => {
    if (e instanceof ApiError) {
      return c.json(
        { success: false, error: { code: e.code, message: e.message }, ...(e.meta ? { meta: e.meta } : {}) },
        e.status as 400,
      );
    }
    console.error('unhandled', e);
    return c.json({ success: false, error: { code: 'INTERNAL', message: 'Internal error' } }, 500);
  });

  return app;
}

const app = createApp();

/** Cron entry (wrangler [triggers]): nightly D1 -> R2 backup. Exported for tests. */
export async function handleScheduled(env: Env): Promise<{ key: string; rows: number }> {
  const s = await runBackup(env);
  console.log(`backup done: ${s.key} (${s.rows} rows)`);
  return { key: s.key, rows: s.rows };
}

export default {
  fetch: app.fetch,
  async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(handleScheduled(env));
  },
};
