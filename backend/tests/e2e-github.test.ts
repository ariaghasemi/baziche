import { describe, it, expect, afterEach, vi } from 'vitest';
import { File as NodeFile } from 'node:buffer';
import { makeCtx, req, registerUser, createProject } from './setup';
import { createGitHubEmu } from './github-emu';
import { decryptOpensslAes256Cbc, sha256Hex, unzipBundleZip } from '../src/lib/bundle';
import { handleScheduled } from '../src/index';

// Full user journey on BINARY_STORAGE=github with an emulated GitHub API +
// an emulated external build server (decrypt/verify/upload like game-build.yml):
// login -> create -> save -> close -> reopen -> assets -> preview -> build ->
// APK -> download. Plus: free-build gate, failed build, retry, UPLOAD_MISSING.

type FileCtorT = new (parts: unknown[], name: string, opts?: { type?: string }) => Blob;
const FileCtor = ((globalThis as unknown as { File?: unknown }).File ?? NodeFile) as unknown as FileCtorT;

const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
const MP3 = new Uint8Array([0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0, 0, 0, 5, 6]);
const FAKE_APK = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0xde, 0xad, 0xbe, 0xef]);

afterEach(() => vi.unstubAllGlobals());

type Ctx = Awaited<ReturnType<typeof makeCtx>>;

async function githubCtx(): Promise<{ ctx: Ctx; emu: ReturnType<typeof createGitHubEmu> }> {
  const ctx = await makeCtx();
  (ctx.env as unknown as Record<string, string>).BINARY_STORAGE = 'github';
  (ctx.env as unknown as Record<string, string>).GITHUB_REPO = 'owner/repo';
  (ctx.env as unknown as Record<string, string>).GITHUB_DISPATCH_TOKEN = 'tok-secret-123';
  const emu = createGitHubEmu();
  vi.stubGlobal('fetch', emu.fetchImpl);
  return { ctx, emu };
}

function buildToken(ctx: Ctx, id: string): string | null {
  const out = ctx.dbData.exec(`SELECT callback_token AS t FROM builds WHERE id = '${id}'`);
  const v = out[0]?.values[0]?.[0];
  return typeof v === 'string' ? v : null;
}

async function uploadFile(ctx: Ctx, token: string, projectId: string, kind: string, name: string, type: string, bytes: Uint8Array) {
  const form = new FormData();
  form.set('projectId', projectId);
  form.set('kind', kind);
  form.set('file', new FileCtor([bytes], name, { type }));
  return ctx.app.request('/api/v1/assets/upload', { method: 'POST', headers: { authorization: `Bearer ${token}` }, body: form }, ctx.env);
}

async function callback(ctx: Ctx, buildId: string, token: string, status: string) {
  return ctx.app.request(
    `/api/v1/builds/${buildId}/callback`,
    { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: JSON.stringify({ status, runId: 'run-1' }) },
    ctx.env,
  );
}

describe('E2E on github storage: user journey to APK', () => {
  it('login -> create -> save -> reopen -> assets -> preview -> build -> download APK', async () => {
    const { ctx, emu } = await githubCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);

    // Save (rev 2) with a real object, then "close + reopen": GET returns it.
    const g1 = (await (await req(ctx, `/api/v1/projects/${pid}`, { token: u.accessToken })).json()) as { json: Record<string, unknown> };
    (g1.json.objects as unknown[]).push({ id: 'obj_hero', name: 'Hero' });
    const patch = await req(ctx, `/api/v1/projects/${pid}`, { method: 'PATCH', token: u.accessToken, body: { baseRev: 1, json: g1.json } });
    expect(patch.status).toBe(200);
    const g2 = (await (await req(ctx, `/api/v1/projects/${pid}`, { token: u.accessToken })).json()) as { project: { rev: number }; json: Record<string, unknown> };
    expect(g2.project.rev).toBe(2);
    expect(JSON.stringify(g2.json)).toContain('obj_hero');

    // Assets: upload png + mp3; mismatched content rejected; presign is r2-only.
    const up1 = await uploadFile(ctx, u.accessToken, pid, 'image', 'hero.png', 'image/png', PNG);
    expect(up1.status).toBe(200);
    const a1 = ((await up1.json()) as { asset: { id: string; key: string } }).asset;
    const up2 = await uploadFile(ctx, u.accessToken, pid, 'audio', 'song.mp3', 'audio/mpeg', MP3);
    expect(up2.status).toBe(200);
    const a2 = ((await up2.json()) as { asset: { id: string; key: string } }).asset;
    const bad = await uploadFile(ctx, u.accessToken, pid, 'image', 'evil.png', 'image/png', new TextEncoder().encode('#!/bin/sh\nnope'));
    expect(bad.status).toBe(400);
    expect(((await bad.json()) as { error: { code: string } }).error.code).toBe('ASSET_TYPE_BLOCKED');
    const pre = await req(ctx, '/api/v1/assets/presign', {
      method: 'POST', token: u.accessToken,
      body: { projectId: pid, kind: 'image', hash: 'ab'.repeat(32), bytes: 12, contentType: 'image/png' },
    });
    expect(pre.status).toBe(400);
    expect(((await pre.json()) as { error: { code: string } }).error.code).toBe('STORAGE_MODE');

    // Preview: list -> signed url -> authless download, bytes match.
    const list = (await (await req(ctx, `/api/v1/assets?projectId=${pid}`, { token: u.accessToken })).json()) as { assets: { id: string }[] };
    expect(list.assets.map((a) => a.id).sort()).toEqual([a1.id, a2.id].sort());
    const urlRes = (await (await req(ctx, `/api/v1/assets/${a1.id}/url`, { token: u.accessToken })).json()) as { url: string };
    expect(urlRes.url).toContain('/api/v1/assets/content');
    expect(urlRes.url).not.toContain('tok-secret-123');
    const dl = await ctx.app.request(urlRes.url, { method: 'GET' }, ctx.env);
    expect(dl.status).toBe(200);
    expect(new Uint8Array(await dl.arrayBuffer())).toEqual(PNG);
    const tampered = new URL(urlRes.url);
    tampered.searchParams.set('token', '0'.repeat(64));
    expect((await ctx.app.request(tampered.href, { method: 'GET' }, ctx.env)).status).toBe(401);

    // Build: dispatched with a github payload (no presigned R2 URLs, no token inside).
    const b = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid, target: 'apk' } });
    expect(b.status).toBe(200);
    const build = ((await b.json()) as { build: { id: string; status: string; dispatch: string } }).build;
    expect(build.status).toBe('BUILDING');
    expect(build.dispatch).toBe('sent');
    expect(emu.dispatches.length).toBe(1);
    const d = emu.dispatches[0];
    expect(d.auth).toBe('Bearer tok-secret-123');
    expect(d.payload['storage']).toBe('github');
    expect(d.payload['releaseTag']).toBe('baziche-storage-builds');
    expect(d.payload['bundleAsset']).toBe(`build-${build.id}-bundle.enc`);
    expect(d.payload['apkAsset']).toBe(`build-${build.id}-game.apk`);
    expect(d.payload['bundleUrl']).toBeUndefined();
    expect(JSON.stringify(d.payload)).not.toContain('tok-secret-123');

    // Emulated external build server (mirrors game-build.yml github branch).
    const tag = d.payload['releaseTag'] as string;
    const enc = emu.downloadAsset(tag, d.payload['bundleAsset'] as string);
    expect(enc).not.toBeNull();
    expect(await sha256Hex(enc!)).toBe(d.payload['bundleSha256']);
    const files = unzipBundleZip(await decryptOpensslAes256Cbc(enc!, d.bundleKey));
    expect(JSON.parse(new TextDecoder().decode(files['game.json'])).formatVersion).toBe(1);
    expect(new TextDecoder().decode(files['game.json'])).toContain('obj_hero');
    expect(JSON.parse(new TextDecoder().decode(files['manifest.json'])).buildId).toBe(build.id);
    expect(files[`assets/${a1.id}`]).toEqual(PNG);
    expect(files[`assets/${a2.id}`]).toEqual(MP3);
    emu.uploadAsset(tag, d.payload['apkAsset'] as string, FAKE_APK, 'application/vnd.android.package-archive');
    emu.uploadAsset(tag, d.payload['logAsset'] as string, new TextEncoder().encode('BUILD SUCCESSFUL'), 'text/plain');

    // Callback: bad token 401; real token COMPLETED (APK verified in release).
    expect((await callback(ctx, build.id, 'wrong', 'COMPLETED')).status).toBe(401);
    const cb = await callback(ctx, build.id, buildToken(ctx, build.id)!, 'COMPLETED');
    expect(((await cb.json()) as { status: string }).status).toBe('COMPLETED');

    // Detail exposes apkUrl; download 302s to the signed proxy; bytes match.
    const detail = (await (await req(ctx, `/api/v1/builds/${build.id}`, { token: u.accessToken })).json()) as { build: { apkUrl: string; status: string } };
    expect(detail.build.status).toBe('COMPLETED');
    expect(detail.build.apkUrl).toContain('/api/v1/builds/content');
    const redir = await req(ctx, `/api/v1/builds/${build.id}/download`, { token: u.accessToken });
    expect(redir.status).toBe(302);
    const loc = redir.headers.get('location')!;
    expect(loc).toContain('/api/v1/builds/content');
    const apk = await ctx.app.request(loc, { method: 'GET' }, ctx.env);
    expect(apk.status).toBe(200);
    expect(apk.headers.get('content-type')).toBe('application/vnd.android.package-archive');
    expect(new Uint8Array(await apk.arrayBuffer())).toEqual(FAKE_APK);

    // Free build consumed: a second build is denied.
    const b2 = await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });
    expect(b2.status).toBe(403);
    expect(((await b2.json()) as { error: { code: string } }).error.code).toBe('FREE_BUILD_UNAVAILABLE');

    // Release strategy: few shared storage releases, never one per file.
    expect([...emu.releases.keys()].sort()).toEqual(['baziche-storage-assets', 'baziche-storage-builds', 'baziche-storage-projects']);
  });

  it('failed build releases the free build; retry works; COMPLETED without APK -> UPLOAD_MISSING', async () => {
    const { ctx, emu } = await githubCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);

    const b1 = ((await (await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } })).json()) as { build: { id: string; status: string } }).build;
    expect(b1.status).toBe('BUILDING');
    const f1 = await callback(ctx, b1.id, buildToken(ctx, b1.id)!, 'FAILED');
    expect(((await f1.json()) as { status: string }).status).toBe('FAILED');

    // Retry allowed (free build released): COMPLETED with no APK uploaded -> UPLOAD_MISSING.
    const b2 = ((await (await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } })).json()) as { build: { id: string; status: string } }).build;
    expect(b2.status).toBe('BUILDING');
    const c2 = await callback(ctx, b2.id, buildToken(ctx, b2.id)!, 'COMPLETED');
    const c2j = (await c2.json()) as { status: string; error: string };
    expect(c2j.status).toBe('FAILED');
    expect(c2j.error).toBe('UPLOAD_MISSING');

    // Retry again, this time the APK lands -> COMPLETED.
    const b3 = ((await (await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } })).json()) as { build: { id: string; status: string } }).build;
    const d3 = emu.dispatches[emu.dispatches.length - 1];
    emu.uploadAsset(d3.payload['releaseTag'] as string, d3.payload['apkAsset'] as string, FAKE_APK, 'application/vnd.android.package-archive');
    const c3 = await callback(ctx, b3.id, buildToken(ctx, b3.id)!, 'COMPLETED');
    expect(((await c3.json()) as { status: string }).status).toBe('COMPLETED');
  });

  it('cron backup + /metrics work on github storage', async () => {
    const { ctx, emu } = await githubCtx();
    const u = await registerUser(ctx);
    await createProject(ctx, u.accessToken);
    const s = await handleScheduled(ctx.env);
    expect(s.key).toContain('backups/');
    expect(s.rows).toBeGreaterThan(0);
    expect(emu.releases.has('baziche-storage-backups')).toBe(true);
    const latest = emu.downloadAsset('baziche-storage-backups', 'latest.json');
    expect(latest).not.toBeNull();
    expect(JSON.parse(new TextDecoder().decode(latest!)).key).toBe(s.key);
    const m = (await (await req(ctx, '/api/v1/meta/metrics')).json()) as { metrics: { lastBackup: { key: string } | null } };
    expect(m.metrics.lastBackup?.key).toBe(s.key);
  });
});
