import { describe, expect, it } from 'vitest';
import { kindAllows, sniff } from '../src/lib/magic';
import { createProject, makeCtx, registerUser, req } from './setup';

const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0]);
const MP3 = new Uint8Array([0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0, 0, 0, 0, 0]);
const TEXT = new TextEncoder().encode('#!/bin/sh\necho hi\npadding...');

describe('magic bytes', () => {
  it('sniffs common formats', () => {
    expect(sniff(PNG)).toBe('png');
    expect(sniff(JPEG)).toBe('jpeg');
    expect(sniff(MP3)).toBe('mp3');
    expect(sniff(TEXT)).toBe('unknown');
    expect(sniff(new Uint8Array([1, 2, 3]))).toBe('unknown');
  });

  it('kind allowlist', () => {
    expect(kindAllows('image', 'png')).toBe(true);
    expect(kindAllows('image', 'mp3')).toBe(false);
    expect(kindAllows('audio', 'mp3')).toBe(true);
    expect(kindAllows('video', 'png')).toBe(false);
  });

  it('commit rejects content/kind mismatch, accepts real png', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120007777', 'magicuser');
    const projectId = await createProject(ctx, s.accessToken, 'A', 'quiz');

    // mp3 bytes declared as image -> blocked
    const p1 = (await (await req(ctx, '/api/v1/assets/presign', {
      method: 'POST', token: s.accessToken,
      body: { projectId, kind: 'image', hash: 'f'.repeat(64), bytes: 100, contentType: 'image/png' },
    })).json()) as { key: string };
    await ctx.r2assets.put(p1.key, MP3);
    const bad = await req(ctx, '/api/v1/assets/commit', {
      method: 'POST', token: s.accessToken,
      body: { projectId, key: p1.key, hash: 'f'.repeat(64), bytes: 100, kind: 'image' },
    });
    expect(bad.status).toBe(400);
    expect(((await bad.json()) as { error: { code: string } }).error.code).toBe('ASSET_TYPE_BLOCKED');

    // real png -> ok
    const p2 = (await (await req(ctx, '/api/v1/assets/presign', {
      method: 'POST', token: s.accessToken,
      body: { projectId, kind: 'image', hash: 'e'.repeat(64), bytes: 100, contentType: 'image/png' },
    })).json()) as { key: string };
    await ctx.r2assets.put(p2.key, PNG);
    const good = await req(ctx, '/api/v1/assets/commit', {
      method: 'POST', token: s.accessToken,
      body: { projectId, key: p2.key, hash: 'e'.repeat(64), bytes: 100, kind: 'image' },
    });
    expect(good.status).toBe(200);
  });

  it('download url: owner gets presigned GET, stranger gets 404, unknown id 404', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120008888', 'dlowner');
    const stranger = await registerUser(ctx, '09120009999', 'dlstranger');
    const projectId = await createProject(ctx, s.accessToken, 'D', 'quiz');
    const p = (await (await req(ctx, '/api/v1/assets/presign', {
      method: 'POST', token: s.accessToken,
      body: { projectId, kind: 'audio', hash: 'd'.repeat(64), bytes: 100, contentType: 'audio/mpeg' },
    })).json()) as { key: string };
    await ctx.r2assets.put(p.key, MP3);
    const cm = (await (await req(ctx, '/api/v1/assets/commit', {
      method: 'POST', token: s.accessToken,
      body: { projectId, key: p.key, hash: 'd'.repeat(64), bytes: 100, kind: 'audio' },
    })).json()) as { asset: { id: string } };

    const ok = await req(ctx, `/api/v1/assets/${cm.asset.id}/url`, { token: s.accessToken });
    expect(ok.status).toBe(200);
    const oj = (await ok.json()) as { url: string; kind: string; expiresIn: number };
    expect(oj.kind).toBe('audio');
    expect(oj.url).toContain('X-Amz-Signature=');
    expect(oj.url).toContain('baziche-assets');

    const denied = await req(ctx, `/api/v1/assets/${cm.asset.id}/url`, { token: stranger.accessToken });
    expect(denied.status).toBe(404);
    const missing = await req(ctx, '/api/v1/assets/ast_nope/url', { token: s.accessToken });
    expect(missing.status).toBe(404);
  });

  it('asset list: owner sees committed assets, stranger denied, projectId required', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120001111', 'lister');
    const stranger = await registerUser(ctx, '09120002222', 'liststranger');
    const projectId = await createProject(ctx, s.accessToken, 'L', 'quiz');
    const empty = (await (await req(ctx, `/api/v1/assets?projectId=${projectId}`, { token: s.accessToken })).json()) as {
      assets: unknown[];
    };
    expect(empty.assets).toEqual([]);

    const p = (await (await req(ctx, '/api/v1/assets/presign', {
      method: 'POST', token: s.accessToken,
      body: { projectId, kind: 'image', hash: 'c'.repeat(64), bytes: 100, contentType: 'image/png' },
    })).json()) as { key: string };
    await ctx.r2assets.put(p.key, PNG);
    await req(ctx, '/api/v1/assets/commit', {
      method: 'POST', token: s.accessToken,
      body: { projectId, key: p.key, hash: 'c'.repeat(64), bytes: 100, kind: 'image' },
    });
    const full = (await (await req(ctx, `/api/v1/assets?projectId=${projectId}`, { token: s.accessToken })).json()) as {
      assets: { id: string; kind: string; hash: string }[];
    };
    expect(full.assets.length).toBe(1);
    expect(full.assets[0].hash).toBe('c'.repeat(64));

    expect((await req(ctx, `/api/v1/assets?projectId=${projectId}`, { token: stranger.accessToken })).status).toBe(404);
    expect((await req(ctx, '/api/v1/assets', { token: s.accessToken })).status).toBe(400);
  });
});
