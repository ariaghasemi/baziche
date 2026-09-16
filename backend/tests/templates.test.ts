// Tier-1 template integrity: every template must be loadable + runnable by :runtime.
// Validates structure, scene/object/event references, capability ids, and variable refs.
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TIER1_IDS } from '../src/registries/game-types';

const DIR = join(dirname(fileURLToPath(import.meta.url)), '../../android/app/src/main/assets/templates');

interface Action {
  capability: string;
  params?: Record<string, unknown>;
}

function collectActions(node: unknown, out: Action[]): void {
  if (Array.isArray(node)) {
    for (const el of node) {
      if (el && typeof el === 'object' && typeof (el as Record<string, unknown>).capability === 'string') {
        out.push(el as Action);
      }
      collectActions(el, out);
    }
  } else if (node && typeof node === 'object') {
    for (const v of Object.values(node as Record<string, unknown>)) collectActions(v, out);
  }
}

function collectDollarRefs(node: unknown, out: Set<string>): void {
  if (typeof node === 'string' && node.startsWith('$') && node.length > 1 && node !== '$last') {
    out.add(node.slice(1));
  } else if (Array.isArray(node)) {
    for (const el of node) collectDollarRefs(el, out);
  } else if (node && typeof node === 'object') {
    for (const v of Object.values(node as Record<string, unknown>)) collectDollarRefs(v, out);
  }
}

describe('tier-1 templates', () => {
  const files = readdirSync(DIR).filter((f) => f.endsWith('.json')).sort();

  it('covers every Tier-1 game type', () => {
    for (const id of TIER1_IDS) expect(files).toContain(`${id}.json`);
  });

  for (const f of files) {
    it(`${f} is runnable`, () => {
      const t = JSON.parse(readFileSync(join(DIR, f), 'utf8')) as Record<string, unknown>;
      expect(t.formatVersion).toBe(1);
      expect((t.meta as Record<string, string>).gameType).toBe(f.replace('.json', ''));

      const scenes = t.scenes as { id: string; entry?: boolean }[];
      expect(scenes.length).toBeGreaterThanOrEqual(1);
      expect(scenes.filter((s) => s.entry).length).toBe(1);
      const sceneIds = new Set(scenes.map((s) => s.id));

      const objects = (t.objects as { id: string; sceneId: string; components?: Record<string, unknown> }[]) ?? [];
      const objIds = new Set(objects.map((o) => o.id));
      for (const o of objects) expect(sceneIds.has(o.sceneId)).toBe(true);

      const events = (t.events as { id: string; trigger: { type: string }; actions: unknown[] }[]) ?? [];
      const eventIds = new Set(events.map((e) => e.id));
      for (const e of events) {
        expect(typeof e.trigger?.type).toBe('string');
        expect(Array.isArray(e.actions)).toBe(true);
      }

      const varNames = new Set(((t.variables as { name: string }[]) ?? []).map((v) => v.name));

      // Button.eventId must reference a real event.
      for (const o of objects) {
        const btn = o.components?.Button as { eventId?: string } | undefined;
        if (btn?.eventId) expect(eventIds.has(btn.eventId)).toBe(true);
      }

      // Capabilities: valid ids within the engine range (CAP-0001..CAP-0032).
      const actions: Action[] = [];
      for (const e of events) collectActions(e.actions, actions);
      const timerStarts = new Set<string>();
      const timerTriggers = new Set<string>();
      for (const a of actions) {
        expect(a.capability).toMatch(/^CAP-\d{4}$/);
        const n = parseInt(a.capability.slice(4), 10);
        expect(n).toBeGreaterThanOrEqual(1);
        expect(n).toBeLessThanOrEqual(32);
        const p = a.params ?? {};
        if (a.capability === 'CAP-0011' && typeof p.name === 'string') timerStarts.add(p.name);
        if (a.capability === 'CAP-0015') expect(sceneIds.has(p.sceneId as string)).toBe(true);
        if (['CAP-0007', 'CAP-0008'].includes(a.capability)) expect(varNames.has(p.name as string)).toBe(true);
        if (['CAP-0020', 'CAP-0021'].includes(a.capability)) expect(varNames.has(p.variable as string)).toBe(true);
      }
      for (const e of events) {
        const trg = e.trigger as { type: string; name?: string };
        if (trg.type === 'timer' && trg.name) timerTriggers.add(trg.name);
      }
      for (const name of timerTriggers) expect(timerStarts.has(name)).toBe(true);

      // $variable references must be declared (nested actions included).
      const refs = new Set<string>();
      for (const e of events) {
        collectDollarRefs(e.actions, refs);
        collectDollarRefs((e as { conditions?: unknown }).conditions, refs);
      }
      for (const r of refs) expect(varNames.has(r)).toBe(true);
    });
  }
});
