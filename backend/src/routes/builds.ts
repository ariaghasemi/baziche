import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, newId, nowSec } from '../lib/errors';
import { parse, zId } from '../lib/validate';
import { requireAuth } from '../middleware/auth';
import {
  assetNameForKey,
  backendOfRow,
  storageFor,
  storageForBackend,
  type BinaryRef,
  type StorageBackend,
} from '../lib/binary-storage';
import { verifyContentToken } from '../lib/content-token';
import { buildBundleZip, encryptOpensslAes256Cbc, sha256Hex, randomHex } from '../lib/bundle';
import { audit } from '../lib/audit';
import { getEntitlement, claimFreeBuild, consumeFreeBuild, releaseFreeBuild } from '../lib/entitlements';

export const buildRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();

const MAX_BUNDLE_ASSET_BYTES = 40 * 1024 * 1024; // game.json + assets embedded in the encrypted bundle

interface OwnedProject {
  id: string;
  name: string;
  rev: number;
  shardId: string;
}

async function ownActiveProject(db: D1Database, userId: string, id: string): Promise<OwnedProject> {
  const p = await db
    .prepare(`SELECT id, name, rev, shard_id AS shardId, user_id AS uid, status FROM projects WHERE id = ?`)
    .bind(id)
    .first<OwnedProject & { uid: string; status: string }>();
  if (!p || p.uid !== userId) throw err('PROJECT_NOT_FOUND', 'Project not found', 404);
  if (p.status !== 'active') throw err('PROJECT_DELETED', 'Project deleted', 410);
  return p;
}

/** Head-revision JSON via its D1-stored storage ref (both backends). */
async function readHeadRevJson(env: Env, projectId: string, rev: number): Promise<unknown | null> {
  const row = await env.DB_DATA.prepare(
    `SELECT r2_key AS r2key, storage, release_tag AS releaseTag, release_id AS releaseId,
            asset_id AS assetId, asset_name AS assetName
     FROM project_revisions WHERE project_id = ? AND rev = ?`,
  )
    .bind(projectId, rev)
    .first<{ r2key: string; storage: string | null; releaseTag: string | null; releaseId: number | null; assetId: number | null; assetName: string | null }>();
  if (!row) return null;
  const ref: BinaryRef = { key: row.r2key, storage: backendOfRow(row.storage, env), releaseTag: row.releaseTag, releaseId: row.releaseId, assetId: row.assetId, assetName: row.assetName };
  const text = await storageForBackend(env, ref.storage).getText(ref);
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** Buildability gate: entry scene present, every action capability is a known CAP-0001..0032. */
export function assertBuildable(json: unknown): asserts json is Record<string, unknown> {
  if (typeof json !== 'object' || json === null) throw err('BUILD_VALIDATION_FAILED', 'Project JSON missing', 400);
  const j = json as Record<string, unknown>;
  if (j.formatVersion !== 1) throw err('BUILD_VALIDATION_FAILED', 'Unsupported formatVersion (expected 1)', 400);
  if (!Array.isArray(j.scenes) || j.scenes.length === 0) {
    throw err('BUILD_VALIDATION_FAILED', 'Project has no scenes', 400, { details: ['scenes is empty'] });
  }
  const details: string[] = [];
  if (!j.scenes.some((s) => typeof s === 'object' && s !== null && (s as Record<string, unknown>).entry === true)) {
    details.push('no entry scene (scenes[].entry === true)');
  }
  if (!Array.isArray(j.objects)) details.push('objects must be an array');
  const caps = new Set<string>();
  if (Array.isArray(j.events)) {
    for (const e of j.events) {
      const actions = (e as Record<string, unknown>)?.actions;
      if (!Array.isArray(actions)) continue;
      for (const a of actions) {
        const cap = (a as Record<string, unknown>)?.capability;
        if (typeof cap === 'string') caps.add(cap);
      }
    }
  }
  for (const cap of [...caps].sort()) {
    const m = /^CAP-(\d{4})$/.exec(cap);
    if (!m || parseInt(m[1], 10) < 1 || parseInt(m[1], 10) > 32) details.push(`unknown capability ${cap} (expected CAP-0001..CAP-0032)`);
  }
  if (details.length > 0) throw err('BUILD_VALIDATION_FAILED', 'Project is not buildable', 400, { details });
}

function appIdFor(projectId: string): string {
  const slug = projectId.toLowerCase().replace(/[^a-z0-9]/g, '').slice(-12) || 'game';
  return `com.baziche.game.p${slug}`;
}

interface DispatchResult {
  status: 'BUILDING' | 'QUEUED';
  dispatch: 'sent' | 'skipped' | 'failed';
}

/**
 * Assemble the encrypted bundle, stage transfer refs, and trigger game-build.yml.
 * Anything missing/misconfigured -> stays QUEUED (honest, retryable) instead of half-built.
 *
 * Payload v2 (docs/GITHUB_BUILD_SETUP.md): `storage` selects the workflow branch.
 * - r2: presigned bundleUrl/apkUrl/aabUrl/logUrl (legacy).
 * - github: releaseTag + asset names; the workflow uses `gh release download/upload`
 *   with its own GITHUB_TOKEN — binaries never transit the Worker.
 */
async function tryDispatch(
  env: Env,
  origin: string,
  buildId: string,
  project: OwnedProject,
  json: Record<string, unknown>,
  target: string,
): Promise<DispatchResult> {
  if (!env.GITHUB_DISPATCH_TOKEN || !env.GITHUB_REPO) return { status: 'QUEUED', dispatch: 'skipped' };
  const now = nowSec();
  try {
    const storage = storageFor(env, origin);

    // 1. Collect asset bytes (best effort per file; bundle cap enforced).
    const files: Record<string, Uint8Array> = {
      'game.json': new TextEncoder().encode(JSON.stringify(json)),
    };
    let total = files['game.json'].length;
    const assetRows = await env.DB_DATA.prepare(
      `SELECT id, r2_key AS r2key, bytes, storage, release_tag AS releaseTag,
              asset_id AS assetId, asset_name AS assetName FROM assets WHERE project_id = ?`,
    )
      .bind(project.id)
      .all<{ id: string; r2key: string; bytes: number; storage: string | null; releaseTag: string | null; assetId: number | null; assetName: string | null }>();
    for (const a of assetRows.results ?? []) {
      if (total + (a.bytes ?? 0) > MAX_BUNDLE_ASSET_BYTES) break;
      const ref: BinaryRef = { key: a.r2key, storage: backendOfRow(a.storage, env), releaseTag: a.releaseTag, assetId: a.assetId, assetName: a.assetName };
      const buf = await storageForBackend(env, ref.storage).get(ref);
      if (!buf) continue;
      files[`assets/${a.id}`] = buf;
      total += buf.length;
    }
    const manifest = { buildId, projectId: project.id, rev: project.rev, files: Object.keys(files) };
    files['manifest.json'] = new TextEncoder().encode(JSON.stringify(manifest));

    // 2. Zip + encrypt + store.
    const bundleKey = randomHex(32);
    const enc = await encryptOpensslAes256Cbc(await buildBundleZip(files), bundleKey);
    const bundleRef = await storage.put('build', `builds/${buildId}/bundle.enc`, enc, 'application/octet-stream');
    const bundleSha256 = await sha256Hex(enc);

    // 3. Transfer refs (logical keys double as the D1 `*_r2_key` columns on both backends).
    const apkKey = `builds/${buildId}/game.apk`;
    const aabKey = `builds/${buildId}/game.aab`;
    const logKey = `builds/${buildId}/build.log`;
    const bundleStorage: StorageBackend = bundleRef.storage;

    // 4. Callback token (per-build secret for the workflow -> backend callback).
    const callbackToken = randomHex(32);
    await env.DB_DATA.prepare(
      `UPDATE builds SET callback_token = ?, apk_r2_key = ?, aab_r2_key = ?, log_r2_key = ?,
              bundle_storage = ?, bundle_key = ?, bundle_release_tag = ?, bundle_asset_id = ?,
              bundle_asset_name = ?, bundle_sha256 = ?, release_tag = ?,
              apk_asset_name = ?, aab_asset_name = ?, log_asset_name = ?
       WHERE id = ?`,
    )
      .bind(
        callbackToken, apkKey, aabKey, logKey,
        bundleStorage, bundleRef.key, bundleRef.releaseTag ?? null, bundleRef.assetId ?? null,
        bundleRef.assetName ?? null, bundleSha256, bundleRef.releaseTag ?? null,
        assetNameForKey('build', apkKey), assetNameForKey('build', aabKey), assetNameForKey('build', logKey),
        buildId,
      )
      .run();

    // 5. Payload from project build{} block + sane defaults.
    const build = (json.build ?? {}) as Record<string, unknown>;
    const meta = (json.meta ?? {}) as Record<string, unknown>;
    const appName = (build.appName as Record<string, string> | undefined)?.fa
      ?? (build.appName as Record<string, string> | undefined)?.en
      ?? (typeof meta.name === 'string' ? meta.name : project.name);
    const payload: Record<string, unknown> = {
      storage: bundleStorage,
      callbackUrl: `${origin}/api/v1/builds/${buildId}/callback`,
      appId: typeof build.applicationId === 'string' && build.applicationId ? build.applicationId : appIdFor(project.id),
      appName: String(appName).slice(0, 60),
      versionCode: Number.isInteger(build.versionCode) ? build.versionCode : 1,
      versionName: typeof build.versionName === 'string' ? build.versionName : '1.0.0',
      targetApi: Number.isInteger(build.targetApi) ? build.targetApi : parseInt(env.DEFAULT_TARGET_API || '36', 10),
      minApi: Number.isInteger(build.minApi) ? build.minApi : parseInt(env.DEFAULT_MIN_API || '26', 10),
      shellRef: env.GAME_BUILD_REF || 'main',
      mode: 'debug', // v1: free builds ship debug APKs; release signing arrives with per-project keystores
    };
    if (bundleStorage === 'github') {
      payload['releaseTag'] = bundleRef.releaseTag;
      payload['bundleAsset'] = bundleRef.assetName;
      payload['bundleSha256'] = bundleSha256;
      payload['apkAsset'] = target === 'aab' ? '' : assetNameForKey('build', apkKey);
      payload['aabAsset'] = target === 'apk' ? '' : assetNameForKey('build', aabKey);
      payload['logAsset'] = assetNameForKey('build', logKey);
    } else {
      // r2 branch: GET presign for the bundle + PUT presigns for the workflow uploads.
      const { presignPutUrl } = await import('../lib/r2');
      const creds = { accountId: env.R2_ACCOUNT_ID ?? '', accessKeyId: env.R2_ACCESS_KEY_ID ?? '', secretAccessKey: env.R2_SECRET_ACCESS_KEY ?? '' };
      const [bundleUrl, apkUrl, aabUrl, logUrl] = await Promise.all([
        storage.getDownloadUrl(bundleRef, 3600),
        presignPutUrl(creds, 'baziche-builds', apkKey, 'application/vnd.android.package-archive', 7200),
        presignPutUrl(creds, 'baziche-builds', aabKey, 'application/octet-stream', 7200),
        presignPutUrl(creds, 'baziche-builds', logKey, 'text/plain', 7200),
      ]);
      payload['bundleUrl'] = bundleUrl;
      payload['bundleSha256'] = bundleSha256;
      payload['apkUrl'] = target === 'aab' ? '' : apkUrl;
      payload['aabUrl'] = target === 'apk' ? '' : aabUrl;
      payload['logUrl'] = logUrl;
    }

    // 6. GitHub workflow dispatch.
    const [owner, repo] = env.GITHUB_REPO.split('/');
    const gh = await fetch(`https://api.github.com/repos/${owner}/${repo}/actions/workflows/game-build.yml/dispatches`, {
      method: 'POST',
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: `Bearer ${env.GITHUB_DISPATCH_TOKEN}`,
        'Content-Type': 'application/json',
        'User-Agent': 'baziche-api',
      },
      body: JSON.stringify({ ref: payload['shellRef'], inputs: { payload: JSON.stringify(payload), bundle_key: bundleKey, callback_token: callbackToken } }),
    });
    if (gh.status !== 204) {
      await env.DB_DATA.prepare('UPDATE builds SET status = ?, error_code = ? WHERE id = ?')
        .bind('QUEUED', 'DISPATCH_REJECTED', buildId)
        .run();
      return { status: 'QUEUED', dispatch: 'failed' };
    }
    await env.DB_DATA.prepare('UPDATE builds SET status = ?, started_at = ? WHERE id = ?').bind('BUILDING', now, buildId).run();
    return { status: 'BUILDING', dispatch: 'sent' };
  } catch (e) {
    await env.DB_DATA.prepare('UPDATE builds SET status = ?, error_code = ? WHERE id = ?')
      .bind('QUEUED', 'DISPATCH_ERROR', buildId)
      .run();
    console.error('dispatch failed', buildId, e instanceof Error ? e.message : e);
    return { status: 'QUEUED', dispatch: 'failed' };
  }
}

/** Verify the APK landed (both backends). In github mode also records the asset id. */
async function verifyApkLanded(env: Env, buildId: string): Promise<boolean> {
  const row = await env.DB_DATA.prepare(
    `SELECT apk_r2_key AS apkKey, bundle_storage AS bundleStorage, release_tag AS releaseTag,
            apk_asset_name AS apkAssetName FROM builds WHERE id = ?`,
  )
    .bind(buildId)
    .first<{ apkKey: string | null; bundleStorage: string | null; releaseTag: string | null; apkAssetName: string | null }>();
  if (!row) return false;
  const backend = backendOfRow(row.bundleStorage, env);
  const apkKey = row.apkKey ?? `builds/${buildId}/game.apk`;
  if (backend === 'github') {
    if (!row.releaseTag || !row.apkAssetName) return false;
    const storage = storageForBackend(env, 'github');
    const found = await (storage as unknown as { findAssetByName(tag: string, name: string): Promise<{ id: number; size: number } | null> }).findAssetByName(row.releaseTag, row.apkAssetName);
    if (!found) return false;
    await env.DB_DATA.prepare('UPDATE builds SET apk_asset_id = ? WHERE id = ?').bind(found.id, buildId).run();
    return true;
  }
  const head = await storageForBackend(env, 'r2').head({ key: apkKey, storage: 'r2' });
  return !!head;
}

async function refundBuild(env: Env, userId: string, buildId: string, spent: string | null): Promise<void> {
  const now = nowSec();
  if (spent === 'free') await releaseFreeBuild(env.DB_AUTH, userId, buildId);
  if (spent === 'oneshot') {
    await env.DB_AUTH.prepare(
      `INSERT INTO entitlements (id, user_id, type, source, expires_at, created_at) VALUES (?, ?, 'build_single', 'refund', NULL, ?)`,
    )
      .bind(newId('ent'), userId, now)
      .run();
  }
}

// NOTE: callback + content routes are mounted BEFORE requireAuth
// (per-build token / HMAC bearer URL respectively).

buildRoutes.post('/:id/callback', async (c) => {
  const id = parse(zId, c.req.param('id'));
  const b = parse(z.object({ status: z.enum(['COMPLETED', 'FAILED']), runId: z.string().min(1).max(64) }), await c.req.json());
  const row = await c.env.DB_DATA.prepare('SELECT id, user_id AS userId, status, callback_token AS token, spent FROM builds WHERE id = ?')
    .bind(id)
    .first<{ id: string; userId: string; status: string; token: string | null; spent: string | null }>();
  if (!row) throw err('BUILD_NOT_FOUND', 'Build not found', 404);
  const h = c.req.header('authorization') ?? '';
  const m = /^Bearer (.+)$/.exec(h);
  if (!row.token || !m || m[1] !== row.token) throw err('TOKEN_INVALID', 'Bad callback token', 401);

  const now = nowSec();
  if (b.status === 'FAILED') {
    await c.env.DB_DATA.prepare("UPDATE builds SET status = 'FAILED', run_ref = ?, finished_at = ?, error_code = 'WORKFLOW_FAILED' WHERE id = ?")
      .bind(b.runId, now, id)
      .run();
    await refundBuild(c.env, row.userId, id, row.spent);
    await audit(c.env.DB_AUTH, 'build.failed', row.userId, { buildId: id, runId: b.runId });
    return c.json({ success: true, status: 'FAILED' });
  }
  // COMPLETED: verify the APK actually landed before telling the user.
  if (!(await verifyApkLanded(c.env, id))) {
    await c.env.DB_DATA.prepare("UPDATE builds SET status = 'FAILED', run_ref = ?, finished_at = ?, error_code = 'UPLOAD_MISSING' WHERE id = ?")
      .bind(b.runId, now, id)
      .run();
    await refundBuild(c.env, row.userId, id, row.spent);
    await audit(c.env.DB_AUTH, 'build.failed', row.userId, { buildId: id, runId: b.runId, reason: 'UPLOAD_MISSING' });
    return c.json({ success: true, status: 'FAILED', error: 'UPLOAD_MISSING' });
  }
  await c.env.DB_DATA.prepare("UPDATE builds SET status = 'COMPLETED', run_ref = ?, finished_at = ? WHERE id = ?")
    .bind(b.runId, now, id)
    .run();
  if (row.spent === 'free') await consumeFreeBuild(c.env.DB_AUTH, row.userId, id);
  await audit(c.env.DB_AUTH, 'build.completed', row.userId, { buildId: id, runId: b.runId });
  return c.json({ success: true, status: 'COMPLETED' });
});

const BUILD_CONTENT_PATH = '/api/v1/builds/content';
const BUILD_CONTENT_FILES: Record<string, { column: string; idColumn: string; contentType: string }> = {
  'game.apk': { column: 'apk_r2_key', idColumn: 'apk_asset_id', contentType: 'application/vnd.android.package-archive' },
  'game.aab': { column: 'aab_r2_key', idColumn: 'aab_asset_id', contentType: 'application/octet-stream' },
  'build.log': { column: 'log_r2_key', idColumn: 'log_asset_id', contentType: 'text/plain' },
};

buildRoutes.get('/content', async (c) => {
  const key = c.req.query('key') ?? '';
  const exp = Number(c.req.query('exp') ?? '');
  const token = c.req.query('token') ?? '';
  const ok = await verifyContentToken(c.env.JWT_SECRET, BUILD_CONTENT_PATH, key, exp, token);
  if (!ok) throw err('TOKEN_INVALID', 'Bad or expired download token', 401);
  const m = /^builds\/([A-Za-z0-9_]+)\/(game\.apk|game\.aab|build\.log)$/.exec(key);
  if (!m) throw err('BUILD_NOT_FOUND', 'Artifact not found', 404);
  const file = BUILD_CONTENT_FILES[m[2]];
  const row = await c.env.DB_DATA.prepare(
    `SELECT ${file.column} AS objKey, bundle_storage AS bundleStorage, release_tag AS releaseTag,
            apk_asset_name AS apkAssetName, aab_asset_name AS aabAssetName, log_asset_name AS logAssetName,
            apk_asset_id AS apkAssetId, aab_asset_id AS aabAssetId, log_asset_id AS logAssetId
     FROM builds WHERE ${file.column} = ?`,
  )
    .bind(key)
    .first<Record<string, string | number | null>>();
  if (!row || !row['objKey']) throw err('BUILD_NOT_FOUND', 'Artifact not found', 404);
  const backend = backendOfRow(row['bundleStorage'] as string | null, c.env);
  const nameCol = m[2] === 'game.apk' ? 'apkAssetName' : m[2] === 'game.aab' ? 'aabAssetName' : 'logAssetName';
  const idCol = m[2] === 'game.apk' ? 'apkAssetId' : m[2] === 'game.aab' ? 'aabAssetId' : 'logAssetId';
  const ref: BinaryRef = {
    key: row['objKey'] as string,
    storage: backend,
    releaseTag: row['releaseTag'] as string | null,
    assetId: row[idCol] as number | null,
    assetName: row[nameCol] as string | null,
  };
  const bytes = await storageForBackend(c.env, backend).get(ref);
  if (!bytes) throw err('BUILD_NOT_FOUND', 'Artifact bytes missing', 404);
  return new Response(bytes as unknown as BodyInit, {
    headers: { 'Content-Type': file.contentType, 'Content-Length': String(bytes.byteLength), 'Cache-Control': 'private, max-age=600' },
  });
});

buildRoutes.use('*', requireAuth);

buildRoutes.post('/', async (c) => {
  const userId = c.get('userId');
  const b = parse(z.object({ projectId: zId, target: z.enum(['apk', 'aab', 'both']).default('apk') }), await c.req.json());
  const project = await ownActiveProject(c.env.DB_DATA, userId, b.projectId);
  const json = await readHeadRevJson(c.env, project.id, project.rev);
  if (json === null) throw err('BUILD_VALIDATION_FAILED', 'Head revision content missing', 400);
  assertBuildable(json);

  const id = newId('bld');
  const now = nowSec();
  await c.env.DB_DATA.prepare(
    `INSERT INTO builds (id, user_id, project_id, rev, provider, status, target, queued_at) VALUES (?, ?, ?, ?, 'github-actions', 'QUEUED', ?, ?)`,
  )
    .bind(id, userId, project.id, project.rev, b.target, now)
    .run();
  // Gate: subscription > one-shot entitlement > free build (single atomic claim).
  const ent = await getEntitlement(c.env.DB_AUTH, userId);
  let spent: 'free' | 'oneshot' | null = null;
  if (!ent.subscribed) {
    const one = await c.env.DB_AUTH.prepare(
      `SELECT id FROM entitlements WHERE user_id = ? AND type = 'build_single' AND (expires_at IS NULL OR expires_at > ?) LIMIT 1`,
    )
      .bind(userId, now)
      .first<{ id: string }>();
    if (one) {
      await c.env.DB_AUTH.prepare('DELETE FROM entitlements WHERE id = ?').bind(one.id).run();
      spent = 'oneshot';
    } else if (ent.freeBuild !== 'AVAILABLE' || !(await claimFreeBuild(c.env.DB_AUTH, userId, id))) {
      await c.env.DB_DATA.prepare('DELETE FROM builds WHERE id = ?').bind(id).run();
      await audit(c.env.DB_AUTH, 'build.denied', userId, { projectId: project.id, freeBuild: ent.freeBuild });
      if (ent.freeBuild === 'RESERVED') throw err('FREE_BUILD_RACE_LOST', 'Another build is using your free build right now', 409);
      throw err('FREE_BUILD_UNAVAILABLE', 'Free build already used — subscription required', 403);
    } else {
      spent = 'free';
    }
    await c.env.DB_DATA.prepare('UPDATE builds SET spent = ? WHERE id = ?').bind(spent, id).run();
  }

  await storageFor(c.env).put('build', `builds/${id}/game.json`, JSON.stringify(json), 'application/json');

  const origin = new URL(c.req.url).origin;
  const d = await tryDispatch(c.env, origin, id, project, json, b.target);
  await audit(c.env.DB_AUTH, 'build.create', userId, { buildId: id, projectId: project.id, status: d.status, dispatch: d.dispatch });
  return c.json({ success: true, build: { id, projectId: project.id, rev: project.rev, status: d.status, dispatch: d.dispatch } });
});

buildRoutes.get('/', async (c) => {
  const userId = c.get('userId');
  const rows = await c.env.DB_DATA.prepare(
    `SELECT id, project_id AS projectId, rev, status, target, run_ref AS runRef,
            error_code AS errorCode, queued_at AS queuedAt, started_at AS startedAt, finished_at AS finishedAt
     FROM builds WHERE user_id = ? ORDER BY queued_at DESC LIMIT 50`,
  )
    .bind(userId)
    .all();
  return c.json({ success: true, builds: rows.results ?? [] });
});

function apkRefOf(row: { apkKey: string | null; bundleStorage: string | null; releaseTag: string | null; apkAssetId: number | null; apkAssetName: string | null }, env: Env): BinaryRef | null {
  if (!row.apkKey) return null;
  return { key: row.apkKey, storage: backendOfRow(row.bundleStorage, env), releaseTag: row.releaseTag, assetId: row.apkAssetId, assetName: row.apkAssetName };
}

buildRoutes.get('/:id', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const row = await c.env.DB_DATA.prepare(
    `SELECT id, user_id AS uid, project_id AS projectId, rev, status, target, run_ref AS runRef,
            apk_r2_key AS apkKey, bundle_storage AS bundleStorage, release_tag AS releaseTag,
            apk_asset_id AS apkAssetId, apk_asset_name AS apkAssetName, error_code AS errorCode,
            queued_at AS queuedAt, started_at AS startedAt, finished_at AS finishedAt
     FROM builds WHERE id = ?`,
  )
    .bind(id)
    .first<Record<string, unknown> & { uid: string; apkKey: string | null; bundleStorage: string | null; releaseTag: string | null; apkAssetId: number | null; apkAssetName: string | null }>();
  if (!row || row.uid !== userId) throw err('BUILD_NOT_FOUND', 'Build not found', 404);
  const { uid: _u, apkKey: _k, bundleStorage: _s, releaseTag: _t, apkAssetId: _ai, apkAssetName: _an, ...rest } = row;
  void [_u, _k, _s, _t, _ai, _an];
  const ref = apkRefOf(row, c.env);
  let apkUrl: string | null = null;
  if (row.status === 'COMPLETED' && ref) {
    try {
      const origin = new URL(c.req.url).origin;
      apkUrl = await storageForBackend(c.env, ref.storage, origin).getDownloadUrl(ref, 3600);
    } catch {
      apkUrl = null;
    }
  }
  return c.json({ success: true, build: { ...rest, apkUrl } });
});

buildRoutes.get('/:id/download', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const row = await c.env.DB_DATA.prepare(
    `SELECT user_id AS uid, status, apk_r2_key AS apkKey, bundle_storage AS bundleStorage,
            release_tag AS releaseTag, apk_asset_id AS apkAssetId, apk_asset_name AS apkAssetName
     FROM builds WHERE id = ?`,
  )
    .bind(id)
    .first<{ uid: string; status: string; apkKey: string | null; bundleStorage: string | null; releaseTag: string | null; apkAssetId: number | null; apkAssetName: string | null }>();
  if (!row || row.uid !== userId) throw err('BUILD_NOT_FOUND', 'Build not found', 404);
  if (row.status !== 'COMPLETED' || !row.apkKey) throw err('BUILD_NOT_FOUND', 'APK not ready yet', 409);
  const ref = apkRefOf(row, c.env);
  if (!ref) throw err('BUILD_NOT_FOUND', 'APK not ready yet', 409);
  const origin = new URL(c.req.url).origin;
  const url = await storageForBackend(c.env, ref.storage, origin).getDownloadUrl(ref, 600);
  return c.redirect(url, 302);
});
