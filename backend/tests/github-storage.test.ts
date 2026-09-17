import { describe, it, expect } from 'vitest';
import {
  GitHubReleaseStorage,
  R2BinaryStorage,
  assetNameForKey,
  backendOfRow,
  contentPathForKey,
  kindForKey,
  storageBackendOf,
  storageFor,
  storageForBackend,
} from '../src/lib/binary-storage';
import { signContentToken, verifyContentToken } from '../src/lib/content-token';
import { makeCtx } from './setup';
import { createGitHubEmu } from './github-emu';
import type { Env } from '../src/index';

function ghEnv(extra: Record<string, unknown> = {}): Env {
  return { BINARY_STORAGE: 'github', GITHUB_REPO: 'owner/repo', GITHUB_DISPATCH_TOKEN: 'tok', JWT_SECRET: 's3cret', ...extra } as unknown as Env;
}

describe('BinaryStorage factory', () => {
  it('defaults to r2, honors BINARY_STORAGE=github, garbage falls back to r2', () => {
    expect(storageBackendOf({} as Env)).toBe('r2');
    expect(storageBackendOf({ BINARY_STORAGE: 'github' } as Env)).toBe('github');
    expect(storageBackendOf({ BINARY_STORAGE: '  GitHub ' } as Env)).toBe('github');
    expect(storageBackendOf({ BINARY_STORAGE: 's3' } as Env)).toBe('r2');
  });

  it('per-shard backend overrides the env default', () => {
    const env = ghEnv();
    expect(storageFor(env, '', 'r2').backend).toBe('r2');
    expect(storageFor(ghEnv({ BINARY_STORAGE: 'r2' }), '', 'github').backend).toBe('github');
  });

  it('github mode without token/repo fails honestly', () => {
    expect(() => storageForBackend({ BINARY_STORAGE: 'github' } as Env, 'github')).toThrow(/GITHUB_REPO/);
  });

  it('r2 mode without bindings fails honestly on use', async () => {
    const s = storageForBackend({ BINARY_STORAGE: 'r2' } as Env, 'r2');
    await expect(s.put('project', 'projects/a/b/r1.json', '{}', 'application/json')).rejects.toThrow(/not configured/);
  });

  it('backendOfRow: row wins, NULL follows env', () => {
    expect(backendOfRow('github', {} as Env)).toBe('github');
    expect(backendOfRow(null, ghEnv())).toBe('github');
    expect(backendOfRow(null, {} as Env)).toBe('r2');
  });
});

describe('key helpers', () => {
  it('kindForKey + contentPathForKey', () => {
    expect(kindForKey('projects/a')).toBe('project');
    expect(kindForKey('assets/a')).toBe('asset');
    expect(kindForKey('builds/a')).toBe('build');
    expect(kindForKey('backups/a')).toBe('backup');
    expect(() => kindForKey('nope/a')).toThrow();
    expect(contentPathForKey('assets/x')).toBe('/api/v1/assets/content');
    expect(contentPathForKey('builds/x')).toBe('/api/v1/builds/content');
    expect(() => contentPathForKey('projects/x')).toThrow();
  });

  it('assetNameForKey is stable and sanitized', () => {
    expect(assetNameForKey('project', 'projects/sh/prj_1/r2.json')).toBe('project-prj_1-r2.json');
    expect(assetNameForKey('asset', 'assets/sh/prj_1/ab12-a_3', 'image/png')).toBe('asset-prj_1-ab12-a_3.png');
    expect(assetNameForKey('asset', 'assets/sh/prj_1/ab12-a_3', 'audio/mpeg')).toBe('asset-prj_1-ab12-a_3.mp3');
    expect(assetNameForKey('build', 'builds/bld_1/game.apk')).toBe('build-bld_1-game.apk');
    expect(assetNameForKey('backup', 'backups/2026-01-01/d1-9.json.gz')).toBe('backup-d1-9.json.gz');
    expect(assetNameForKey('backup', 'backups/latest.json')).toBe('latest.json');
    expect(assetNameForKey('asset', 'assets/weird name/../x', 'image/png')).not.toContain(' ');
    expect(assetNameForKey('asset', 'assets/weird name/../x', 'image/png')).not.toContain('..');
  });
});

describe('content tokens', () => {
  it('sign/verify roundtrip; tamper + expiry + wrong path rejected', async () => {
    const t = await signContentToken('sec', '/api/v1/assets/content', 'assets/k', 9999999999);
    expect(await verifyContentToken('sec', '/api/v1/assets/content', 'assets/k', 9999999999, t)).toBe(true);
    expect(await verifyContentToken('sec', '/api/v1/assets/content', 'assets/other', 9999999999, t)).toBe(false);
    expect(await verifyContentToken('sec', '/api/v1/builds/content', 'assets/k', 9999999999, t)).toBe(false);
    expect(await verifyContentToken('wrong', '/api/v1/assets/content', 'assets/k', 9999999999, t)).toBe(false);
    expect(await verifyContentToken('sec', '/api/v1/assets/content', 'assets/k', 1, t)).toBe(false);
    expect(await verifyContentToken('sec', '/api/v1/assets/content', 'assets/k', 9999999999, t.slice(0, -1) + (t.endsWith('0') ? '1' : '0'))).toBe(false);
    expect(await verifyContentToken('', '/api/v1/assets/content', 'assets/k', 9999999999, t)).toBe(false);
  });
});

describe('GitHubReleaseStorage (emulated API)', () => {
  function storage(emu: ReturnType<typeof createGitHubEmu>, opts: Record<string, unknown> = {}): GitHubReleaseStorage {
    return new GitHubReleaseStorage({ owner: 'o', repo: 'r', token: 'PTOKEN_SECRET_X7', origin: 'https://api.test', hmacSecret: 's3cret', fetchImpl: emu.fetchImpl, ...opts });
  }

  it('put/get/getText/head/delete roundtrip; one release per kind, not per file', async () => {
    const emu = createGitHubEmu();
    const s = storage(emu);
    const r1 = await s.put('project', 'projects/sh/prj_1/r1.json', '{"a":1}', 'application/json');
    expect(r1.storage).toBe('github');
    expect(r1.releaseTag).toBe('baziche-storage-projects');
    expect(r1.assetName).toBe('project-prj_1-r1.json');
    expect(r1.assetId).toBeGreaterThan(0);
    expect(r1.sha256).toMatch(/^[0-9a-f]{64}$/);

    const r2 = await s.put('project', 'projects/sh/prj_1/r2.json', '{"a":2}', 'application/json');
    expect(r2.releaseTag).toBe('baziche-storage-projects');
    expect(emu.releases.size).toBe(1); // shared storage release, not one per file

    expect(await s.getText(r1)).toBe('{"a":1}');
    expect(new TextDecoder().decode((await s.get(r2))!)).toBe('{"a":2}');
    expect((await s.head(r1))?.size).toBe(7);
    await s.delete(r1);
    expect(await s.get(r1)).toBeNull();
    expect(await s.head(r1)).toBeNull();
  });

  it('same key twice replaces (no duplicate assets)', async () => {
    const emu = createGitHubEmu();
    const s = storage(emu);
    await s.put('backup', 'backups/latest.json', '{"at":1}', 'application/json');
    await s.put('backup', 'backups/latest.json', '{"at":2}', 'application/json');
    const rel = emu.releases.get('baziche-storage-backups')!;
    expect(rel.assets.size).toBe(1);
    expect(await s.getText({ key: 'backups/latest.json', storage: 'github', releaseTag: 'baziche-storage-backups', assetName: 'latest.json' })).toBe('{"at":2}');
  });

  it('rolls to -2/-3 near the asset cap and reads via stored tags', async () => {
    const emu = createGitHubEmu();
    const s = storage(emu, { maxAssetsPerRelease: 2 });
    const refs = [];
    for (let i = 1; i <= 5; i++) {
      refs.push(await s.put('asset', `assets/sh/p/h${i}-a_${i}`, new Uint8Array([i]), 'image/png'));
    }
    expect(refs.map((r) => r.releaseTag)).toEqual([
      'baziche-storage-assets',
      'baziche-storage-assets',
      'baziche-storage-assets-2',
      'baziche-storage-assets-2',
      'baziche-storage-assets-3',
    ]);
    for (let i = 0; i < 5; i++) {
      expect((await s.get(refs[i]))![0]).toBe(i + 1);
    }
  });

  it('resolves by (tag, name) when assetId is unknown; unknown names return null', async () => {
    const emu = createGitHubEmu();
    const s = storage(emu);
    await s.put('build', 'builds/bld_1/game.apk', new Uint8Array([9, 9]), 'application/vnd.android.package-archive');
    const byName = { key: 'builds/bld_1/game.apk', storage: 'github' as const, releaseTag: 'baziche-storage-builds', assetName: 'build-bld_1-game.apk' };
    expect((await s.get(byName))!.length).toBe(2);
    expect((await s.head(byName))?.size).toBe(2);
    expect(await s.findAssetByName('baziche-storage-builds', 'build-bld_1-game.apk')).not.toBeNull();
    expect(await s.findAssetByName('baziche-storage-builds', 'nope.apk')).toBeNull();
    expect(await s.findAssetByName('no-such-tag', 'x')).toBeNull();
    expect(await s.get({ key: 'builds/bld_1/game.apk', storage: 'github', releaseTag: 'baziche-storage-builds', assetName: 'nope.apk' })).toBeNull();
  });

  it('getDownloadUrl mints a verifiable HMAC proxy URL (no token inside)', async () => {
    const emu = createGitHubEmu();
    const s = storage(emu);
    const ref = await s.put('asset', 'assets/sh/p/h1-a_1', new Uint8Array([1]), 'image/png');
    const url = await s.getDownloadUrl(ref, 600);
    expect(url).not.toContain('PTOKEN_SECRET_X7');
    const u = new URL(url);
    expect(u.origin).toBe('https://api.test');
    expect(u.pathname).toBe('/api/v1/assets/content');
    expect(u.searchParams.get('key')).toBe('assets/sh/p/h1-a_1');
    const exp = Number(u.searchParams.get('exp'));
    expect(exp).toBeGreaterThan(Math.floor(Date.now() / 1000));
    expect(await verifyContentToken('s3cret', u.pathname, 'assets/sh/p/h1-a_1', exp, u.searchParams.get('token')!)).toBe(true);
  });

  it('getDownloadUrl needs origin + secret (honest error)', async () => {
    const emu = createGitHubEmu();
    const s = new GitHubReleaseStorage({ owner: 'o', repo: 'r', token: 't', fetchImpl: emu.fetchImpl });
    const ref = await s.put('asset', 'assets/sh/p/h1-a_1', new Uint8Array([1]), 'image/png');
    await expect(s.getDownloadUrl(ref, 60)).rejects.toThrow(/origin/);
  });
});

describe('R2BinaryStorage (test fakes)', () => {
  it('put/get/head/delete/getDownloadUrl roundtrip', async () => {
    const ctx = await makeCtx();
    const s = storageForBackend(ctx.env, 'r2');
    const ref = await s.put('asset', 'assets/sh/p/h1-a_1', new Uint8Array([5, 6]), 'image/png');
    expect(ref).toEqual({ key: 'assets/sh/p/h1-a_1', storage: 'r2', size: 2 });
    expect((await s.get(ref))!).toEqual(new Uint8Array([5, 6]));
    expect((await s.head(ref))?.size).toBe(2);
    const url = await s.getDownloadUrl(ref, 60);
    expect(url).toContain('baziche-assets');
    expect(url).toContain('X-Amz-Signature=');
    await s.delete(ref);
    expect(await s.get(ref)).toBeNull();
  });
});
