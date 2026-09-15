import { describe, expect, it } from 'vitest';
import { makeCtx, registerUser, req } from './setup';

describe('projects', () => {
  it('create -> get -> patch(rev++) -> conflict(409) -> revisions -> delete', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx);

    const created = await req(ctx, '/api/v1/projects', { method: 'POST', token: s.accessToken, body: { name: 'My Puzzle', gameType: 'puzzle' } });
    expect(created.status).toBe(200);
    const cj = (await created.json()) as { project: { id: string; rev: number; tier1: boolean } };
    expect(cj.project.rev).toBe(1);
    expect(cj.project.tier1).toBe(true);
    const id = cj.project.id;

    const got = await req(ctx, `/api/v1/projects/${id}`, { token: s.accessToken });
    const gj = (await got.json()) as { project: { rev: number }; json: { formatVersion: number; meta: { name: string } } };
    expect(gj.project.rev).toBe(1);
    expect(gj.json.formatVersion).toBe(1);
    expect(gj.json.meta.name).toBe('My Puzzle');

    const patched = await req(ctx, `/api/v1/projects/${id}`, {
      method: 'PATCH',
      token: s.accessToken,
      body: { baseRev: 1, json: { ...gj.json, meta: { ...gj.json.meta, name: 'Renamed' } } },
    });
    expect(patched.status).toBe(200);
    expect(((await patched.json()) as { rev: number }).rev).toBe(2);

    const stale = await req(ctx, `/api/v1/projects/${id}`, {
      method: 'PATCH',
      token: s.accessToken,
      body: { baseRev: 1, json: gj.json },
    });
    expect(stale.status).toBe(409);
    const sj = (await stale.json()) as { error: { code: string }; serverRev: number; serverCopy: { meta: { name: string } } };
    expect(sj.error.code).toBe('REVISION_CONFLICT');
    expect(sj.serverRev).toBe(2);
    expect(sj.serverCopy.meta.name).toBe('Renamed');

    const revs = await req(ctx, `/api/v1/projects/${id}/revisions`, { token: s.accessToken });
    expect((((await revs.json()) as { revisions: unknown[] }).revisions).length).toBe(2);

    const del = await req(ctx, `/api/v1/projects/${id}`, { method: 'DELETE', token: s.accessToken });
    expect(del.status).toBe(200);
    const gone = await req(ctx, `/api/v1/projects/${id}`, { token: s.accessToken });
    expect(gone.status).toBe(410);
  });

  it('rejects unknown game type and bad json', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09127777777', 'projtester');
    const bad = await req(ctx, '/api/v1/projects', { method: 'POST', token: s.accessToken, body: { name: 'X', gameType: 'nope' } });
    expect(((await bad.json()) as { error: { code: string } }).error.code).toBe('UNKNOWN_GAME_TYPE');

    const ok = await req(ctx, '/api/v1/projects', { method: 'POST', token: s.accessToken, body: { name: 'Q', gameType: 'quiz' } });
    const id = ((await ok.json()) as { project: { id: string } }).project.id;
    const invalid = await req(ctx, `/api/v1/projects/${id}`, { method: 'PATCH', token: s.accessToken, body: { baseRev: 1, json: { formatVersion: 99 } } });
    expect(((await invalid.json()) as { error: { code: string } }).error.code).toBe('INVALID_PROJECT_JSON');
  });

  it('users cannot see each other projects', async () => {
    const ctx = await makeCtx();
    const a = await registerUser(ctx, '09128888888', 'user_a');
    const b = await registerUser(ctx, '09129999999', 'user_b');
    const created = await req(ctx, '/api/v1/projects', { method: 'POST', token: a.accessToken, body: { name: 'Secret', gameType: 'quiz' } });
    const id = ((await created.json()) as { project: { id: string } }).project.id;
    const peek = await req(ctx, `/api/v1/projects/${id}`, { token: b.accessToken });
    expect(peek.status).toBe(404);
    const list = (await (await req(ctx, '/api/v1/projects', { token: b.accessToken })).json()) as { projects: unknown[] };
    expect(list.projects.length).toBe(0);
  });

  it('assets presign validates, commit verifies object exists', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120001111', 'assetuser');
    const created = await req(ctx, '/api/v1/projects', { method: 'POST', token: s.accessToken, body: { name: 'A', gameType: 'quiz' } });
    const projectId = ((await created.json()) as { project: { id: string } }).project.id;

    const blocked = await req(ctx, '/api/v1/assets/presign', {
      method: 'POST',
      token: s.accessToken,
      body: { projectId, kind: 'image', hash: 'e'.repeat(64), bytes: 100, contentType: 'application/x-sh' },
    });
    expect(((await blocked.json()) as { error: { code: string } }).error.code).toBe('ASSET_TYPE_BLOCKED');

    const pres = await req(ctx, '/api/v1/assets/presign', {
      method: 'POST',
      token: s.accessToken,
      body: { projectId, kind: 'image', hash: 'e'.repeat(64), bytes: 100, contentType: 'image/png' },
    });
    expect(pres.status).toBe(200);
    const pj = (await pres.json()) as { key: string; uploadUrl: string };
    expect(pj.key.startsWith('assets/')).toBe(true);
    expect(pj.uploadUrl).toContain('baziche-assets');

    const early = await req(ctx, '/api/v1/assets/commit', {
      method: 'POST',
      token: s.accessToken,
      body: { projectId, key: pj.key, hash: 'e'.repeat(64), bytes: 100, kind: 'image' },
    });
    expect(early.status).toBe(404); // nothing uploaded yet

    await ctx.r2assets.put(pj.key, 'fake-png-bytes'); // simulate client PUT to presigned URL
    const done = await req(ctx, '/api/v1/assets/commit', {
      method: 'POST',
      token: s.accessToken,
      body: { projectId, key: pj.key, hash: 'e'.repeat(64), bytes: 100, kind: 'image' },
    });
    expect(done.status).toBe(200);
  });

  it('admin skeleton: forbidden for users, works for admins', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120002222', 'normaluser');
    const denied = await req(ctx, '/api/v1/admin/stats', { token: s.accessToken });
    expect(denied.status).toBe(403);

    ctx.db.run('INSERT INTO admins (user_id, role, created_at) VALUES (?, ?, ?)', [s.user.id, 'admin', 1]);
    const stats = await req(ctx, '/api/v1/admin/stats', { token: s.accessToken });
    expect(stats.status).toBe(200);
    const sj = (await stats.json()) as { stats: { users: number; projects: number } };
    expect(sj.stats.users).toBe(1);

    const users = (await (await req(ctx, '/api/v1/admin/users', { token: s.accessToken })).json()) as {
      users: Record<string, unknown>[];
    };
    expect(users.users[0].server_hash).toBeUndefined();
    expect(users.users[0].phone).toBe('+989120002222');
  });
});
