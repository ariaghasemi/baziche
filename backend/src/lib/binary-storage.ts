// BinaryStorage — the ONLY abstraction over byte storage (R2 <-> GitHub swap point).
//
// Routes NEVER touch R2Bucket or the GitHub API directly; they call this
// interface plus `storageRouter.keyFor` (key naming) and D1 (metadata/refs).
// Backend selection: `BINARY_STORAGE` env var ('r2' default, 'github' for MVP),
// overridable per shard via storage_shards.backend (NULL = env decides).
//
// Logical keys keep the existing `keyFor` shape (`projects/...`, `assets/...`,
// `builds/...`, `backups/...`) on BOTH backends, so D1 `r2_key` columns stay
// valid as logical keys and rollback is a config flip, not a data migration.

import { presignGetUrl, type R2Creds } from './r2';
import { signContentToken } from './content-token';
import type { Env } from '../index';

export type BinaryKind = 'project' | 'asset' | 'build' | 'backup';
export type StorageBackend = 'r2' | 'github';

/** Storage release tags: `baziche-storage-{projects,assets,builds,backups}[-N]`. */
export const STORAGE_TAG_PREFIX = 'baziche-storage';
export const BACKUPS_TAG = `${STORAGE_TAG_PREFIX}-backups`;
/** Rollover to `-2`, `-3`, ... before GitHub's 1000-assets-per-release cap. */
export const MAX_ASSETS_PER_RELEASE = 900;

/** D1-persisted reference to one binary object (see migration 004). */
export interface BinaryRef {
  /** Logical key (`projects/...`, `assets/...`, `builds/...`, `backups/...`). */
  key: string;
  storage: StorageBackend;
  releaseTag?: string | null;
  releaseId?: number | null;
  assetId?: number | null;
  assetName?: string | null;
  size?: number | null;
  sha256?: string | null;
}

export interface HeadInfo {
  size: number;
}

export interface BinaryStorage {
  readonly backend: StorageBackend;
  put(kind: BinaryKind, key: string, data: Uint8Array | ArrayBuffer | string, contentType: string): Promise<BinaryRef>;
  get(ref: BinaryRef): Promise<Uint8Array | null>;
  getText(ref: BinaryRef): Promise<string | null>;
  head(ref: BinaryRef): Promise<HeadInfo | null>;
  delete(ref: BinaryRef): Promise<void>;
  /** Short-lived URL fetchable with a plain GET (no auth headers). */
  getDownloadUrl(ref: BinaryRef, expiresSec: number): Promise<string>;
}

export function kindForKey(key: string): BinaryKind {
  if (key.startsWith('projects/')) return 'project';
  if (key.startsWith('assets/')) return 'asset';
  if (key.startsWith('builds/')) return 'build';
  if (key.startsWith('backups/')) return 'backup';
  throw new Error(`unrecognized storage key: ${key.slice(0, 64)}`);
}

const R2_BUCKET_NAMES: Record<BinaryKind, string> = {
  project: 'baziche-projects',
  asset: 'baziche-assets',
  build: 'baziche-builds',
  backup: 'baziche-builds',
};

// ---------------------------------------------------------------------------
// R2 implementation (original backend; kept for rollback via BINARY_STORAGE=r2)
// ---------------------------------------------------------------------------

export class R2BinaryStorage implements BinaryStorage {
  readonly backend: StorageBackend = 'r2';

  constructor(
    private buckets: Record<BinaryKind, R2Bucket | undefined>,
    private creds: R2Creds,
  ) {}

  private bucketFor(kind: BinaryKind): R2Bucket {
    const b = this.buckets[kind];
    if (!b) throw new Error(`R2 bucket for ${kind} is not configured (BINARY_STORAGE=r2 needs R2 bindings)`);
    return b;
  }

  private credsOrThrow(): R2Creds {
    if (!this.creds.accountId || !this.creds.accessKeyId || !this.creds.secretAccessKey) {
      throw new Error('R2 credentials missing');
    }
    return this.creds;
  }

  async put(kind: BinaryKind, key: string, data: Uint8Array | ArrayBuffer | string, contentType: string): Promise<BinaryRef> {
    const body = typeof data === 'string' ? data : data instanceof Uint8Array ? data : new Uint8Array(data);
    await this.bucketFor(kind).put(key, body as Uint8Array, { httpMetadata: { contentType } });
    const size = typeof body === 'string' ? new TextEncoder().encode(body).length : (body as Uint8Array).byteLength;
    return { key, storage: 'r2', size };
  }

  async get(ref: BinaryRef): Promise<Uint8Array | null> {
    const obj = await this.bucketFor(kindForKey(ref.key)).get(ref.key);
    if (!obj) return null;
    return new Uint8Array(await obj.arrayBuffer());
  }

  async getText(ref: BinaryRef): Promise<string | null> {
    const obj = await this.bucketFor(kindForKey(ref.key)).get(ref.key);
    if (!obj) return null;
    return await obj.text();
  }

  async head(ref: BinaryRef): Promise<HeadInfo | null> {
    const h = await this.bucketFor(kindForKey(ref.key)).head(ref.key);
    return h ? { size: h.size } : null;
  }

  async delete(ref: BinaryRef): Promise<void> {
    await this.bucketFor(kindForKey(ref.key)).delete(ref.key);
  }

  async getDownloadUrl(ref: BinaryRef, expiresSec: number): Promise<string> {
    return presignGetUrl(this.credsOrThrow(), R2_BUCKET_NAMES[kindForKey(ref.key)], ref.key, expiresSec);
  }
}

// ---------------------------------------------------------------------------
// GitHub Releases implementation (MVP backend while R2 is unavailable)
// ---------------------------------------------------------------------------

export interface GitHubStorageOpts {
  owner: string;
  repo: string;
  /** PAT with Contents read+write on this repo (GITHUB_DISPATCH_TOKEN). Never logged. */
  token: string;
  tagPrefix?: string;
  maxAssetsPerRelease?: number;
  /** Public Worker origin, for minting HMAC download URLs. */
  origin?: string;
  hmacSecret?: string;
  fetchImpl?: typeof fetch;
}

interface GhRelease {
  id: number;
  tag_name: string;
}

interface GhAsset {
  id: number;
  name: string;
  size: number;
}

const EXT_BY_CONTENT_TYPE: Record<string, string> = {
  'image/png': '.png',
  'image/jpeg': '.jpg',
  'image/webp': '.webp',
  'audio/mpeg': '.mp3',
  'audio/ogg': '.ogg',
  'audio/wav': '.wav',
  'audio/mp4': '.m4a',
  'audio/x-wav': '.wav',
  'font/ttf': '.ttf',
  'font/otf': '.otf',
  'font/woff': '.woff',
  'font/woff2': '.woff2',
  'video/mp4': '.mp4',
  'application/octet-stream': '.bin',
};

/**
 * Deterministic, collision-free asset filename for a logical key. The mapping
 * is injective (keys are unique: rev-unique, hash+random-unique, build-unique,
 * timestamp-unique) EXCEPT `backups/latest.json`, which intentionally replaces.
 */
export function assetNameForKey(kind: BinaryKind, key: string, contentType = 'application/octet-stream'): string {
  const segs = key.split('/').filter(Boolean);
  const safe = (s: string): string => s.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/\.{2,}/g, '-').slice(0, 120);
  if (kind === 'project' && segs.length >= 4 && segs[0] === 'projects') {
    return safe(`project-${segs[2]}-${segs[3]}`); // project-<id>-r<rev>.json
  }
  if (kind === 'asset' && segs.length >= 4 && segs[0] === 'assets') {
    return safe(`asset-${segs[2]}-${segs[3]}${EXT_BY_CONTENT_TYPE[contentType] ?? ''}`);
  }
  if (kind === 'build' && segs.length >= 3 && segs[0] === 'builds') {
    return safe(`build-${segs[1]}-${segs[segs.length - 1]}`); // build-<id>-game.apk ...
  }
  if (kind === 'backup' && key === 'backups/latest.json') return 'latest.json';
  if (kind === 'backup' && segs[0] === 'backups') {
    return safe(`backup-${segs[segs.length - 1]}`); // backup-d1-<at>.json.gz
  }
  return safe(`${kind}-${segs.join('-')}`);
}

/** Worker proxy route serving authless downloads for a key (assets/builds only). */
export function contentPathForKey(key: string): string {
  if (key.startsWith('assets/')) return '/api/v1/assets/content';
  if (key.startsWith('builds/')) return '/api/v1/builds/content';
  throw new Error('no public download for this key kind');
}

function toHex(b: ArrayBuffer): string {
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

async function sha256HexBytes(data: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', data as unknown as ArrayBuffer);
  return toHex(digest);
}

export class GitHubReleaseStorage implements BinaryStorage {
  readonly backend: StorageBackend = 'github';

  private owner: string;
  private repo: string;
  private token: string;
  private tagPrefix: string;
  private maxAssets: number;
  private origin: string;
  private hmacSecret: string;
  private doFetch: typeof fetch;

  constructor(o: GitHubStorageOpts) {
    if (!o.owner || !o.repo || !o.token) throw new Error('github storage needs owner + repo + token');
    this.owner = o.owner;
    this.repo = o.repo;
    this.token = o.token;
    this.tagPrefix = o.tagPrefix ?? STORAGE_TAG_PREFIX;
    this.maxAssets = o.maxAssetsPerRelease ?? MAX_ASSETS_PER_RELEASE;
    this.origin = o.origin ?? '';
    this.hmacSecret = o.hmacSecret ?? '';
    this.doFetch = o.fetchImpl ?? fetch;
  }

  private api(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers ?? {});
    headers.set('Accept', 'application/vnd.github+json');
    headers.set('Authorization', `Bearer ${this.token}`);
    headers.set('User-Agent', 'baziche-api');
    return this.doFetch(`https://api.github.com/repos/${this.owner}/${this.repo}${path}`, { ...init, headers });
  }

  /** GET release by tag, creating it (empty, private-repo) when missing. */
  private async ensureRelease(tag: string): Promise<{ id: number; tag: string }> {
    const r = await this.api(`/releases/tags/${encodeURIComponent(tag)}`);
    if (r.status === 200) {
      const j = (await r.json()) as GhRelease;
      return { id: j.id, tag };
    }
    if (r.status !== 404) throw new Error(`github release lookup failed (${r.status})`);
    const c = await this.api('/releases', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        tag_name: tag,
        name: tag,
        body: 'BAZICHE binary storage (Worker-managed). Do not delete.',
        draft: false,
        prerelease: false,
      }),
    });
    if (c.status !== 201) throw new Error(`github release create failed (${c.status})`);
    const j = (await c.json()) as GhRelease;
    return { id: j.id, tag };
  }

  private async countAssets(releaseId: number): Promise<number> {
    let n = 0;
    for (let page = 1; page <= 11; page++) {
      const r = await this.api(`/releases/${releaseId}/assets?per_page=100&page=${page}`);
      if (!r.ok) throw new Error(`github asset list failed (${r.status})`);
      const items = (await r.json()) as GhAsset[];
      n += items.length;
      if (n >= this.maxAssets || items.length < 100) break;
    }
    return n;
  }

  /** Find an asset by exact name (any release tag). Exported for callback verification. */
  async findAssetByName(tag: string, name: string): Promise<{ id: number; size: number } | null> {
    const r = await this.api(`/releases/tags/${encodeURIComponent(tag)}`);
    if (r.status === 404) return null;
    if (!r.ok) throw new Error(`github release lookup failed (${r.status})`);
    const rel = (await r.json()) as GhRelease;
    for (let page = 1; page <= 11; page++) {
      const l = await this.api(`/releases/${rel.id}/assets?per_page=100&page=${page}`);
      if (!l.ok) throw new Error(`github asset list failed (${l.status})`);
      const items = (await l.json()) as GhAsset[];
      const hit = items.find((a) => a.name === name);
      if (hit) return { id: hit.id, size: hit.size };
      if (items.length < 100) break;
    }
    return null;
  }

  private async resolveAssetId(ref: BinaryRef): Promise<number | null> {
    if (ref.assetId) return ref.assetId;
    if (ref.releaseTag && ref.assetName) {
      return (await this.findAssetByName(ref.releaseTag, ref.assetName))?.id ?? null;
    }
    return null;
  }

  async put(kind: BinaryKind, key: string, data: Uint8Array | ArrayBuffer | string, contentType: string): Promise<BinaryRef> {
    const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data instanceof Uint8Array ? data : new Uint8Array(data);
    const name = assetNameForKey(kind, key, contentType);
    const baseTag = `${this.tagPrefix}-${kind}s`;
    // Backups never roll: ~1 asset/day + an in-place `latest.json` (documented).
    const rollover = kind !== 'backup';
    let rel: { id: number; tag: string } | null = null;
    for (let i = 0; i < 10; i++) {
      const tag = i === 0 ? baseTag : `${baseTag}-${i + 1}`;
      const cand = await this.ensureRelease(tag);
      if (!rollover || (await this.countAssets(cand.id)) < this.maxAssets) {
        rel = cand;
        break;
      }
    }
    if (!rel) throw new Error('github storage releases exhausted');
    const existing = await this.findAssetByName(rel.tag, name);
    if (existing) {
      await this.api(`/releases/assets/${existing.id}`, { method: 'DELETE' });
    }
    const up = await this.doFetch(
      `https://uploads.github.com/repos/${this.owner}/${this.repo}/releases/${rel.id}/assets?name=${encodeURIComponent(name)}`,
      {
        method: 'POST',
        headers: {
          Accept: 'application/vnd.github+json',
          Authorization: `Bearer ${this.token}`,
          'Content-Type': contentType,
          'Content-Length': String(bytes.byteLength),
          'User-Agent': 'baziche-api',
        },
        body: bytes as unknown as BodyInit,
      },
    );
    if (up.status !== 201) throw new Error(`github asset upload failed (${up.status})`);
    const asset = (await up.json()) as GhAsset;
    return {
      key,
      storage: 'github',
      releaseTag: rel.tag,
      releaseId: rel.id,
      assetId: asset.id,
      assetName: asset.name,
      size: asset.size,
      sha256: await sha256HexBytes(bytes),
    };
  }

  async get(ref: BinaryRef): Promise<Uint8Array | null> {
    const assetId = await this.resolveAssetId(ref);
    if (!assetId) return null;
    const r = await this.doFetch(`https://api.github.com/repos/${this.owner}/${this.repo}/releases/assets/${assetId}`, {
      headers: { Accept: 'application/octet-stream', Authorization: `Bearer ${this.token}`, 'User-Agent': 'baziche-api' },
    });
    if (r.status === 404) return null;
    if (!r.ok) throw new Error(`github asset download failed (${r.status})`);
    return new Uint8Array(await r.arrayBuffer());
  }

  async getText(ref: BinaryRef): Promise<string | null> {
    const b = await this.get(ref);
    return b ? new TextDecoder().decode(b) : null;
  }

  async head(ref: BinaryRef): Promise<HeadInfo | null> {
    const assetId = await this.resolveAssetId(ref);
    if (!assetId) return null;
    const r = await this.api(`/releases/assets/${assetId}`);
    if (r.status === 404) return null;
    if (!r.ok) throw new Error(`github asset lookup failed (${r.status})`);
    return { size: ((await r.json()) as GhAsset).size };
  }

  async delete(ref: BinaryRef): Promise<void> {
    const assetId = await this.resolveAssetId(ref);
    if (!assetId) return;
    await this.api(`/releases/assets/${assetId}`, { method: 'DELETE' });
  }

  async getDownloadUrl(ref: BinaryRef, expiresSec: number): Promise<string> {
    if (!this.origin || !this.hmacSecret) throw new Error('github download URLs need origin + signing secret');
    const path = contentPathForKey(ref.key);
    const exp = Math.floor(Date.now() / 1000) + expiresSec;
    const token = await signContentToken(this.hmacSecret, path, ref.key, exp);
    return `${this.origin}${path}?key=${encodeURIComponent(ref.key)}&exp=${exp}&token=${token}`;
  }
}

// ---------------------------------------------------------------------------
// Factory
// ---------------------------------------------------------------------------

export function storageBackendOf(env: Env): StorageBackend {
  return (env.BINARY_STORAGE ?? 'r2').trim().toLowerCase() === 'github' ? 'github' : 'r2';
}

export function storageForBackend(env: Env, backend: StorageBackend, origin = ''): BinaryStorage {
  if (backend === 'github') {
    const [owner, repo] = (env.GITHUB_REPO ?? '').split('/');
    if (!owner || !repo || !env.GITHUB_DISPATCH_TOKEN) {
      throw new Error('github storage needs GITHUB_REPO + GITHUB_DISPATCH_TOKEN secrets');
    }
    return new GitHubReleaseStorage({ owner, repo, token: env.GITHUB_DISPATCH_TOKEN, origin, hmacSecret: env.JWT_SECRET });
  }
  return new R2BinaryStorage(
    { project: env.R2_PROJECTS, asset: env.R2_ASSETS, build: env.R2_BUILDS, backup: env.R2_BUILDS },
    { accountId: env.R2_ACCOUNT_ID ?? '', accessKeyId: env.R2_ACCESS_KEY_ID ?? '', secretAccessKey: env.R2_SECRET_ACCESS_KEY ?? '' },
  );
}

/** Write-path factory: per-shard override wins, else the `BINARY_STORAGE` env default. */
export function storageFor(env: Env, origin = '', shardBackend?: string | null): BinaryStorage {
  const b: StorageBackend = shardBackend === 'github' || shardBackend === 'r2' ? shardBackend : storageBackendOf(env);
  return storageForBackend(env, b, origin);
}

/** Read-path: rows remember their backend; legacy NULL rows follow the env default. */
export function backendOfRow(rowStorage: string | null | undefined, env: Env): StorageBackend {
  if (rowStorage === 'github' || rowStorage === 'r2') return rowStorage;
  return storageBackendOf(env);
}
