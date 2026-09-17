import { Hono } from 'hono';
import type { Env } from '../index';
import { BACKUPS_TAG, backendOfRow, storageFor } from '../lib/binary-storage';
import { CAPABILITIES, REGISTRIES_VERSION } from '../registries/capabilities';
import { FEATURES } from '../registries/features';
import { GAME_TYPES } from '../registries/game-types';
import { COMPONENT_NAMES } from '../registries/components';

export const metaRoutes = new Hono<{ Bindings: Env }>();

// Public, versioned, cacheable registries for Editor/Runtime clients.
metaRoutes.get('/registries', (c) => {
  c.header('Cache-Control', 'public, max-age=3600');
  return c.json({
    success: true,
    version: REGISTRIES_VERSION,
    capabilities: CAPABILITIES,
    features: FEATURES,
    gameTypes: GAME_TYPES,
    components: COMPONENT_NAMES,
  });
});

metaRoutes.get('/plans', async (c) => {
  const rows = await c.env.DB_AUTH.prepare('SELECT id, title, price_toman AS priceToman, days, active FROM plans ORDER BY days ASC').all();
  return c.json({ success: true, plans: rows.results ?? [] });
});

// Public ops counters (no PII — aggregate counts only). Scraped by uptime monitors.
metaRoutes.get('/metrics', async (c) => {
  c.header('Cache-Control', 'public, max-age=60');
  const [users, projects, builds, subs, lastBackup] = await Promise.all([
    c.env.DB_AUTH.prepare('SELECT COUNT(*) AS n FROM users').first<{ n: number }>(),
    c.env.DB_DATA.prepare("SELECT COUNT(*) AS n FROM projects WHERE status = 'active'").first<{ n: number }>(),
    c.env.DB_DATA.prepare('SELECT status, COUNT(*) AS n FROM builds GROUP BY status').all<{ status: string; n: number }>(),
    c.env.DB_AUTH.prepare("SELECT COUNT(*) AS n FROM subscriptions WHERE status = 'active'").first<{ n: number }>(),
    storageFor(c.env)
      .getText({ key: 'backups/latest.json', storage: backendOfRow(null, c.env), releaseTag: BACKUPS_TAG, assetName: 'latest.json' })
      .catch(() => null),
  ]);
  const buildsByStatus: Record<string, number> = {};
  for (const b of builds.results ?? []) buildsByStatus[b.status] = b.n;
  let backup: unknown = null;
  try {
    backup = lastBackup ? JSON.parse(lastBackup) : null;
  } catch {
    backup = null;
  }
  return c.json({
    success: true,
    ts: Math.floor(Date.now() / 1000),
    metrics: { users: users?.n ?? 0, activeProjects: projects?.n ?? 0, buildsByStatus, activeSubscriptions: subs?.n ?? 0, lastBackup: backup },
  });
});
