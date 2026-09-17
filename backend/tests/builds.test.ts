import { describe, it, expect, afterEach, vi } from 'vitest';
import { makeCtx, req, registerUser, createProject } from './setup';
import { decryptOpensslAes256Cbc, sha256Hex, unzipBundleZip } from '../src/lib/bundle';

afterEach(() => vi.unstubAllGlobals());

function buildToken(ctx: Awaited<ReturnType<typeof makeCtx>>, id: string): string | null {
  const out = ctx.dbData.exec(`SELECT callback_token AS t FROM builds WHERE id = '${id}'`);
  const v = out[0]?.values[0]?.[0];
  return typeof v === 'string' ? v : null;
}

describe('POST /api/v1/builds (minimal)', () => {
  it('creates a QUEUED build without GitHub config (dispatch skipped)', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    const res = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });
    expect(res.status).toBe(200);
    const j = (await res.json()) as { build: { id: string; status: string; dispatch: string } };
    expect(j.build.status).toBe('QUEUED');
    expect(j.build.dispatch).toBe('skipped');
    // game.json snapshot stored
    const snap = await ctx.r2builds.get(`builds/${j.build.id}/game.json`);
    expect(snap).not.toBeNull();
    expect(JSON.parse(await snap!.text()).formatVersion).toBe(1);
  });

  it('404s for foreign/missing projects', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const res = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: 'prj_nope' } });
    expect(res.status).toBe(404);
  });

  it('rejects projects with no entry scene (BUILD_VALIDATION_FAILED)', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    const g = await req(ctx, `/api/v1/projects/${pid}`, { token: u.accessToken });
    const gj = (await g.json()) as { json: Record<string, unknown> };
    const scenes = gj.json.scenes as Record<string, unknown>[];
    scenes.forEach((s) => { s.entry = false; });
    const patch = await req(ctx, `/api/v1/projects/${pid}`, { method: 'PATCH', token: u.accessToken, body: { baseRev: 1, json: gj.json } });
    expect(patch.status).toBe(200);
    const res = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });
    expect(res.status).toBe(400);
    const j = (await res.json()) as { error: { code: string } };
    expect(j.error.code).toBe('BUILD_VALIDATION_FAILED');
  });

  it('rejects unknown capabilities (CAP-0099)', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    const g = await req(ctx, `/api/v1/projects/${pid}`, { token: u.accessToken });
    const gj = (await g.json()) as { json: Record<string, unknown> };
    gj.json.events = [{ id: 'e1', trigger: { type: 'start' }, actions: [{ capability: 'CAP-0099', params: {} }] }];
    const patch = await req(ctx, `/api/v1/projects/${pid}`, { method: 'PATCH', token: u.accessToken, body: { baseRev: 1, json: gj.json } });
    expect(patch.status).toBe(200);
    const res = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });
    expect(res.status).toBe(400);
    const j = (await res.json()) as { error: { code: string } };
    expect(j.error.code).toBe('BUILD_VALIDATION_FAILED');
  });

  it('dispatches to GitHub when configured: BUILDING + encrypted bundle + sha (fetch stubbed)', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    (ctx.env as unknown as Record<string, string>).GITHUB_TOKEN = 'gh_test';
    (ctx.env as unknown as Record<string, string>).GITHUB_REPO = 'owner/repo';
    let dispatchBody = '';
    let dispatchUrl = '';
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: { body?: string }) => {
      dispatchUrl = url;
      dispatchBody = init.body ?? '';
      return new Response(null, { status: 204 });
    }));
    const res = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });
    expect(res.status).toBe(200);
    const j = (await res.json()) as { build: { id: string; status: string; dispatch: string } };
    expect(j.build.status).toBe('BUILDING');
    expect(j.build.dispatch).toBe('sent');
    expect(dispatchUrl).toBe('https://api.github.com/repos/owner/repo/actions/workflows/game-build.yml/dispatches');
    const input = JSON.parse(dispatchBody).inputs as { payload: string; bundle_key: string; callback_token: string };
    const payload = JSON.parse(input.payload) as Record<string, string>;
    expect(payload.mode).toBe('debug');
    expect(payload.callbackUrl).toContain(`/api/v1/builds/${j.build.id}/callback`);
    expect(input.callback_token).toBe(buildToken(ctx, j.build.id));
    // bundle.enc exists, sha matches, decrypts with bundle_key, contains game.json
    const encObj = await ctx.r2builds.get(`builds/${j.build.id}/bundle.enc`);
    expect(encObj).not.toBeNull();
    const enc = new Uint8Array(await encObj!.arrayBuffer());
    expect(await sha256Hex(enc)).toBe(payload.bundleSha256);
    const zip = await decryptOpensslAes256Cbc(enc, input.bundle_key);
    const files = unzipBundleZip(zip);
    expect(Object.keys(files).sort()).toEqual(['assets', 'game.json', 'manifest.json'].filter((f) => f in files || f === 'game.json' || f === 'manifest.json'));
    expect(JSON.parse(new TextDecoder().decode(files['game.json'])).formatVersion).toBe(1);
  });

  it('stays QUEUED when GitHub rejects the dispatch', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    (ctx.env as unknown as Record<string, string>).GITHUB_TOKEN = 'gh_bad';
    (ctx.env as unknown as Record<string, string>).GITHUB_REPO = 'owner/repo';
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 404 })));
    const res = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });
    const j = (await res.json()) as { build: { status: string; dispatch: string } };
    expect(j.build.status).toBe('QUEUED');
    expect(j.build.dispatch).toBe('failed');
  });
});

describe('build callback + download', () => {
  async function dispatched() {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    (ctx.env as unknown as Record<string, string>).GITHUB_TOKEN = 'gh_test';
    (ctx.env as unknown as Record<string, string>).GITHUB_REPO = 'owner/repo';
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })));
    const res = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });
    const j = (await res.json()) as { build: { id: string } };
    return { ctx, u, buildId: j.build.id };
  }

  it('rejects callbacks with a bad token', async () => {
    const { ctx, buildId } = await dispatched();
    const res = await ctx.app.request(
      `/api/v1/builds/${buildId}/callback`,
      { method: 'POST', headers: { authorization: 'Bearer wrong', 'content-type': 'application/json' }, body: JSON.stringify({ status: 'COMPLETED', runId: '1' }) },
      ctx.env,
    );
    expect(res.status).toBe(401);
  });

  it('COMPLETED requires the APK in R2, then download 302s', async () => {
    const { ctx, u, buildId } = await dispatched();
    const token = buildToken(ctx, buildId)!;
    const cb = (status: string) =>
      ctx.app.request(
        `/api/v1/builds/${buildId}/callback`,
        { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: JSON.stringify({ status, runId: '777' }) },
        ctx.env,
      );
    // no APK uploaded -> FAILED/UPLOAD_MISSING
    let r = await cb('COMPLETED');
    expect(((await r.json()) as { status: string }).status).toBe('FAILED');
    // workflow uploads the APK, retries callback
    await ctx.r2builds.put(`builds/${buildId}/game.apk`, new Uint8Array([0x50, 0x4b]));
    r = await cb('COMPLETED');
    expect(((await r.json()) as { status: string }).status).toBe('COMPLETED');
    const detail = await req(ctx, `/api/v1/builds/${buildId}`, { token: u.accessToken });
    const dj = (await detail.json()) as { build: { status: string; apkUrl: string | null } };
    expect(dj.build.status).toBe('COMPLETED');
    expect(dj.build.apkUrl).toContain('X-Amz-Signature=');
    const dl = await req(ctx, `/api/v1/builds/${buildId}/download`, { token: u.accessToken });
    expect(dl.status).toBe(302);
  });

  it('FAILED callback marks the build failed', async () => {
    const { ctx, buildId } = await dispatched();
    const token = buildToken(ctx, buildId)!;
    const r = await ctx.app.request(
      `/api/v1/builds/${buildId}/callback`,
      { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: JSON.stringify({ status: 'FAILED', runId: '9' }) },
      ctx.env,
    );
    expect(((await r.json()) as { status: string }).status).toBe('FAILED');
  });

  it('lists own builds only', async () => {
    const ctx = await makeCtx();
    const a = await registerUser(ctx, '09111111111', 'alice');
    const b = await registerUser(ctx, '09222222222', 'bob');
    const pa = await createProject(ctx, a.accessToken, 'A');
    await req(ctx, '/api/v1/builds', { method: 'POST', token: a.accessToken, body: { projectId: pa } });
    const res = await req(ctx, '/api/v1/builds', { token: b.accessToken });
    const j = (await res.json()) as { builds: unknown[] };
    expect(j.builds).toEqual([]);
  });
});
