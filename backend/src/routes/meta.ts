import { Hono } from 'hono';
import type { Env } from '../index';
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
  const rows = await c.env.DB.prepare('SELECT id, title, price_toman AS priceToman, days, active FROM plans ORDER BY days ASC').all();
  return c.json({ success: true, plans: rows.results ?? [] });
});
