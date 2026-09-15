import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { CAPABILITIES, CAPABILITY_IDS } from '../src/registries/capabilities';
import { FEATURE_IDS, FEATURES } from '../src/registries/features';
import { GAME_TYPES } from '../src/registries/game-types';
import { COMPONENT_SET } from '../src/registries/components';
import { makeCtx, req } from './setup';

describe('registries integrity', () => {
  it('capability ids unique and well-formed', () => {
    const ids = CAPABILITIES.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(id).toMatch(/^CAP-\d{4}$/);
    expect(CAPABILITIES.length).toBeGreaterThanOrEqual(30);
  });

  it('features reference existing capabilities', () => {
    expect(FEATURES.length).toBe(20);
    for (const f of FEATURES) for (const cap of f.capabilities) {
      expect(CAPABILITY_IDS.has(cap), `feature ${f.id} -> ${cap}`).toBe(true);
    }
  });

  it('20 game types, 8 in tier 1, valid feature refs', () => {
    expect(GAME_TYPES.length).toBe(20);
    expect(GAME_TYPES.filter((g) => g.tier === 1).length).toBe(8);
    expect(GAME_TYPES.filter((g) => g.tier === 2).length).toBe(8);
    expect(GAME_TYPES.filter((g) => g.tier === 3).length).toBe(4);
    for (const g of GAME_TYPES) {
      for (const f of g.features) expect(FEATURE_IDS.has(f), `${g.id} -> ${f}`).toBe(true);
      for (const comp of g.defaultComponents) expect(COMPONENT_SET.has(comp), `${g.id} -> ${comp}`).toBe(true);
    }
  });

  it('component catalog matches shared/registries/components.json', () => {
    const shared = JSON.parse(readFileSync(new URL('../../shared/registries/components.json', import.meta.url), 'utf8')) as {
      components: { name: string }[];
    };
    const names = shared.components.map((c) => c.name);
    expect(names.length).toBe(26);
    for (const n of names) expect(COMPONENT_SET.has(n)).toBe(true);
  });

  it('GET /meta/registries serves versioned snapshot', async () => {
    const ctx = await makeCtx();
    const res = await req(ctx, '/api/v1/meta/registries');
    expect(res.status).toBe(200);
    const j = (await res.json()) as { version: number; capabilities: unknown[]; gameTypes: unknown[] };
    expect(j.version).toBe(1);
    expect(j.capabilities.length).toBeGreaterThanOrEqual(30);
    expect(j.gameTypes.length).toBe(20);
  });
});
