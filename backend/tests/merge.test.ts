import { describe, expect, it } from 'vitest';
import { threeWayMerge } from '../src/lib/merge';
import { createProject, makeCtx, registerUser, req } from './setup';

function baseDoc() {
  return {
    formatVersion: 1,
    meta: { name: 'G', gameType: 'quiz' },
    settings: { locale: 'fa' },
    scenes: [{ id: 's1', name: 'Main' }],
    objects: [{ id: 'o1', kind: 'rect' }],
    events: [],
    variables: [{ name: 'score', type: 'number' }],
    assets: [],
  };
}

describe('threeWayMerge', () => {
  it('merges non-overlapping changes cleanly', () => {
    const base = baseDoc();
    const mine = { ...base, scenes: [{ id: 's1', name: 'Main' }, { id: 's2', name: 'Mine' }] };
    const theirs = { ...base, objects: [{ id: 'o1', kind: 'rect' }, { id: 'o2', kind: 'text' }] };
    const { merged, conflicts } = threeWayMerge(base, mine, theirs) as { merged: typeof base; conflicts: string[] };
    expect(conflicts).toEqual([]);
    expect(merged.scenes.length).toBe(2);
    expect(merged.objects.length).toBe(2);
  });

  it('flags scalar conflicts, server wins', () => {
    const base = baseDoc();
    const mine = { ...base, meta: { name: 'Mine', gameType: 'quiz' } };
    const theirs = { ...base, meta: { name: 'Theirs', gameType: 'quiz' } };
    const { merged, conflicts } = threeWayMerge(base, mine, theirs) as { merged: typeof base; conflicts: string[] };
    expect(conflicts).toContain('meta');
    expect(merged.meta.name).toBe('Theirs');
  });

  it('merges same id edited on one side only', () => {
    const base = baseDoc();
    const mine = { ...base, objects: [{ id: 'o1', kind: 'circle' }] };
    const { merged, conflicts } = threeWayMerge(base, mine, base) as { merged: typeof base; conflicts: string[] };
    expect(conflicts).toEqual([]);
    expect(merged.objects[0].kind).toBe('circle');
  });

  it('flags same id edited on both sides', () => {
    const base = baseDoc();
    const mine = { ...base, objects: [{ id: 'o1', kind: 'circle' }] };
    const theirs = { ...base, objects: [{ id: 'o1', kind: 'text' }] };
    const { merged, conflicts } = threeWayMerge(base, mine, theirs) as { merged: typeof base; conflicts: string[] };
    expect(conflicts).toContain('objects.o1');
    expect(merged.objects[0].kind).toBe('text');
  });

  it('delete vs modify is flagged, survivor kept', () => {
    const base = baseDoc();
    const mine = { ...base, objects: [] };
    const theirs = { ...base, objects: [{ id: 'o1', kind: 'text' }] };
    const { merged, conflicts } = threeWayMerge(base, mine, theirs) as { merged: typeof base; conflicts: string[] };
    expect(conflicts.some((c) => c.startsWith('objects.o1'))).toBe(true);
    expect(merged.objects.length).toBe(1);
  });

  it('formatVersion mismatch refuses to merge', () => {
    const base = baseDoc();
    const mine = { ...base, formatVersion: 2 };
    const { conflicts } = threeWayMerge(base, mine, base);
    expect(conflicts).toContain('formatVersion');
  });
});

describe('merge + restore endpoints', () => {
  it('conflict -> merge -> save merged as new rev', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120005555', 'merger');
    const id = await createProject(ctx, s.accessToken, 'M', 'quiz');

    const g1 = (await (await req(ctx, `/api/v1/projects/${id}`, { token: s.accessToken })).json()) as {
      project: { rev: number }; json: Record<string, unknown>;
    };
    // Simulate device B saving rev 2 (adds a scene).
    const rev2json = JSON.parse(JSON.stringify(g1.json)) as typeof g1.json;
    (rev2json.scenes as unknown[]).push({ id: 's2', name: 'B', entry: false, background: {}, objectIds: [], transitions: [] });
    const p2 = await req(ctx, `/api/v1/projects/${id}`, { method: 'PATCH', token: s.accessToken, body: { baseRev: 1, json: rev2json } });
    expect(p2.status).toBe(200);

    // Device A (base rev 1) adds an object -> merge should combine both.
    const mine = JSON.parse(JSON.stringify(g1.json)) as Record<string, unknown>;
    (mine.objects as unknown[]).push({ id: 'o9', sceneId: 'scene_main', kind: 'rect', components: {}, visible: true, layer: 0 });
    const m = await req(ctx, `/api/v1/projects/${id}/merge`, { method: 'POST', token: s.accessToken, body: { baseRev: 1, json: mine } });
    expect(m.status).toBe(200);
    const mj = (await m.json()) as { merged: Record<string, unknown>; conflicts: string[]; serverRev: number };
    expect(mj.conflicts).toEqual([]);
    expect((mj.merged.scenes as unknown[]).length).toBe(2);
    expect((mj.merged.objects as unknown[]).length).toBe(1);

    const p3 = await req(ctx, `/api/v1/projects/${id}`, { method: 'PATCH', token: s.accessToken, body: { baseRev: mj.serverRev, json: mj.merged } });
    expect(p3.status).toBe(200);
    expect(((await p3.json()) as { rev: number }).rev).toBe(3);
  });

  it('restore creates a new rev from old content', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120006666', 'restorer');
    const id = await createProject(ctx, s.accessToken, 'R', 'quiz');
    const g1 = (await (await req(ctx, `/api/v1/projects/${id}`, { token: s.accessToken })).json()) as {
      project: { rev: number }; json: Record<string, unknown>;
    };
    const rev2 = JSON.parse(JSON.stringify(g1.json)) as Record<string, unknown>;
    (rev2.meta as Record<string, unknown>).name = 'Renamed';
    await req(ctx, `/api/v1/projects/${id}`, { method: 'PATCH', token: s.accessToken, body: { baseRev: 1, json: rev2 } });

    const r = await req(ctx, `/api/v1/projects/${id}/restore`, { method: 'POST', token: s.accessToken, body: { rev: 1, baseRev: 2 } });
    expect(r.status).toBe(200);
    expect(((await r.json()) as { rev: number }).rev).toBe(3);

    const g3 = (await (await req(ctx, `/api/v1/projects/${id}`, { token: s.accessToken })).json()) as {
      json: { meta: { name: string } };
    };
    expect(g3.json.meta.name).toBe('R'); // back to rev-1 content
  });
});
