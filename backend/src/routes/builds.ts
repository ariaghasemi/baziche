import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, newId, nowSec } from '../lib/errors';
import { parse, zId } from '../lib/validate';
import { requireAuth } from '../middleware/auth';
import { storageRouter } from '../lib/storage-router';
import { getText, putText, presignGetUrl, presignPutUrl, type R2Creds } from '../lib/r2';
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

function revKey(shardId: string, projectId: string, rev: number): string {
  return storageRouter.keyFor({ id: shardId } as never, 'project', shardId, projectId, `r${rev}.json`);
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

function r2Creds(env: Env): R2Creds {
  return { accountId: env.R2_ACCOUNT_ID, accessKeyId: env.R2_ACCESS_KEY_ID, secretAccessKey: env.R2_SECRET_ACCESS_KEY };
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
 * Assemble the encrypted bundle, presign transfer URLs, and trigger game-build.yml.
 * Anything missing/misconfigured -> stays QUEUED (honest, retryable) instead of half-built.
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
    // 1. Collect asset bytes (best effort per file; bundle cap enforced).
    const files: Record<string, Uint8Array> = {
      'game.json': new TextEncoder().encode(JSON.stringify(json)),
    };
    let total = files['game.json'].length;
    const assetRows = await env.DB_DATA.prepare('SELECT id, r2_key AS r2key, bytes FROM assets WHERE project_id = ?')
      .bind(project.id)
      .all<{ id: string; r2key: string; bytes: number }>();
    for (const a of assetRows.results ?? []) {
      if (total + (a.bytes ?? 0) > MAX_BUNDLE_ASSET_BYTES) break;
      const obj = await env.R2_ASSETS.get(a.r2key);
      if (!obj) continue;
      const buf = new Uint8Array(await obj.arrayBuffer());
      files[`assets/${a.id}`] = buf;
      total += buf.length;
    }
    const manifest = { buildId, projectId: project.id, rev: project.rev, files: Object.keys(files) };
    files['manifest.json'] = new TextEncoder().encode(JSON.stringify(manifest));

    // 2. Zip + encrypt + store.
    const bundleKey = randomHex(32);
    const enc = await encryptOpensslAes256Cbc(await buildBundleZip(files), bundleKey);
    const bundleR2Key = `builds/${buildId}/bundle.enc`;
    await env.R2_BUILDS.put(bundleR2Key, enc);
    const bundleSha256 = await sha256Hex(enc);

    // 3. Presigned transfer URLs.
    const creds = r2Creds(env);
    const apkR2Key = `builds/${buildId}/game.apk`;
    const aabR2Key = `builds/${buildId}/game.aab`;
    const logR2Key = `builds/${buildId}/build.log`;
    const [bundleUrl, apkUrl, aabUrl, logUrl] = await Promise.all([
      presignGetUrl(creds, 'baziche-builds', bundleR2Key, 3600),
      presignPutUrl(creds, 'baziche-builds', apkR2Key, 'application/vnd.android.package-archive', 7200),
      presignPutUrl(creds, 'baziche-builds', aabR2Key, 'application/octet-stream', 7200),
      presignPutUrl(creds, 'baziche-builds', logR2Key, 'text/plain', 7200),
    ]);

    // 4. Callback token (per-build secret for the workflow -> backend callback).
    const callbackToken = randomHex(32);
    await env.DB_DATA.prepare('UPDATE builds SET callback_token = ?, apk_r2_key = ?, aab_r2_key = ?, log_r2_key = ? WHERE id = ?')
      .bind(callbackToken, apkR2Key, aabR2Key, logR2Key, buildId)
      .run();

    // 5. Payload from project build{} block + sane defaults.
    const build = (json.build ?? {}) as Record<string, unknown>;
    const meta = (json.meta ?? {}) as Record<string, unknown>;
    const appName = (build.appName as Record<string, string> | undefined)?.fa
      ?? (build.appName as Record<string, string> | undefined)?.en
      ?? (typeof meta.name === 'string' ? meta.name : project.name);
    const payload = {
      bundleUrl,
      bundleSha256,
      apkUrl: target === 'aab' ? '' : apkUrl,
      aabUrl: target === 'apk' ? '' : aabUrl,
      logUrl,
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
      body: JSON.stringify({ ref: payload.shellRef, inputs: { payload: JSON.stringify(payload), bundle_key: bundleKey, callback_token: callbackToken } }),
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

// NOTE: callback route is mounted BEFORE requireAuth (it uses its own per-build token).

buildRoutes.post('/:id/callback', async (c) => {
  const id = parse(zId, c.req.param('id'));
  const b = parse(z.object({ status: z.enum(['COMPLETED', 'FAILED']), runId: z.string().min(1).max(64) }), await c.req.json());
  const row = await c.env.DB_DATA.prepare('SELECT id, user_id AS userId, status, apk_r2_key AS apkKey, callback_token AS token, spent FROM builds WHERE id = ?')
    .bind(id)
    .first<{ id: string; userId: string; status: string; apkKey: string | null; token: string | null; spent: string | null }>();
  if (!row) throw err('BUILD_NOT_FOUND', 'Build not found', 404);
  const h = c.req.header('authorization') ?? '';
  const m = /^Bearer (.+)$/.exec(h);
  if (!row.token || !m || m[1] !== row.token) throw err('TOKEN_INVALID', 'Bad callback token', 401);

  const now = nowSec();
  if (b.status === 'FAILED') {
    await c.env.DB_DATA.prepare("UPDATE builds SET status = 'FAILED', run_ref = ?, finished_at = ?, error_code = 'WORKFLOW_FAILED' WHERE id = ?")
      .bind(b.runId, now, id)
      .run();
    if (row.spent === 'free') await releaseFreeBuild(c.env.DB_AUTH, row.userId, id);
    if (row.spent === 'oneshot') {
      await c.env.DB_AUTH.prepare(
        `INSERT INTO entitlements (id, user_id, type, source, expires_at, created_at) VALUES (?, ?, 'build_single', 'refund', NULL, ?)`,
      )
        .bind(newId('ent'), row.userId, now)
        .run();
    }
    await audit(c.env.DB_AUTH, 'build.failed', row.userId, { buildId: id, runId: b.runId });
    return c.json({ success: true, status: 'FAILED' });
  }
  // COMPLETED: verify the APK actually landed in R2 before telling the user.
  // (Dispatched builds store apk_r2_key; QUEUED/manual builds fall back to the conventional key.)
  const apkKey = row.apkKey ?? `builds/${id}/game.apk`;
  const head = await c.env.R2_BUILDS.head(apkKey);
  if (!head) {
    await c.env.DB_DATA.prepare("UPDATE builds SET status = 'FAILED', run_ref = ?, finished_at = ?, error_code = 'UPLOAD_MISSING' WHERE id = ?")
      .bind(b.runId, now, id)
      .run();
    if (row.spent === 'free') await releaseFreeBuild(c.env.DB_AUTH, row.userId, id);
    if (row.spent === 'oneshot') {
      await c.env.DB_AUTH.prepare(
        `INSERT INTO entitlements (id, user_id, type, source, expires_at, created_at) VALUES (?, ?, 'build_single', 'refund', NULL, ?)`,
      )
        .bind(newId('ent'), row.userId, now)
        .run();
    }
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

buildRoutes.use('*', requireAuth);

buildRoutes.post('/', async (c) => {
  const userId = c.get('userId');
  const b = parse(z.object({ projectId: zId, target: z.enum(['apk', 'aab', 'both']).default('apk') }), await c.req.json());
  const project = await ownActiveProject(c.env.DB_DATA, userId, b.projectId);
  const text = await getText(c.env.R2_PROJECTS, revKey(project.shardId, project.id, project.rev));
  if (!text) throw err('BUILD_VALIDATION_FAILED', 'Head revision content missing', 400);
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    throw err('BUILD_VALIDATION_FAILED', 'Head revision is not JSON', 400);
  }
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

  await putText(c.env.R2_BUILDS, `builds/${id}/game.json`, JSON.stringify(json));

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

buildRoutes.get('/:id', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const row = await c.env.DB_DATA.prepare(
    `SELECT id, user_id AS uid, project_id AS projectId, rev, status, target, run_ref AS runRef,
            apk_r2_key AS apkKey, error_code AS errorCode,
            queued_at AS queuedAt, started_at AS startedAt, finished_at AS finishedAt
     FROM builds WHERE id = ?`,
  )
    .bind(id)
    .first<Record<string, unknown> & { uid: string; apkKey: string | null }>();
  if (!row || row.uid !== userId) throw err('BUILD_NOT_FOUND', 'Build not found', 404);
  const { uid: _u, apkKey, ...rest } = row;
  void _u;
  let apkUrl: string | null = null;
  if (row.status === 'COMPLETED' && apkKey) {
    try {
      apkUrl = await presignGetUrl(r2Creds(c.env), 'baziche-builds', apkKey, 3600);
    } catch {
      apkUrl = null;
    }
  }
  return c.json({ success: true, build: { ...rest, apkUrl } });
});

buildRoutes.get('/:id/download', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const row = await c.env.DB_DATA.prepare('SELECT user_id AS uid, status, apk_r2_key AS apkKey FROM builds WHERE id = ?')
    .bind(id)
    .first<{ uid: string; status: string; apkKey: string | null }>();
  if (!row || row.uid !== userId) throw err('BUILD_NOT_FOUND', 'Build not found', 404);
  if (row.status !== 'COMPLETED' || !row.apkKey) throw err('BUILD_NOT_FOUND', 'APK not ready yet', 409);
  const url = await presignGetUrl(r2Creds(c.env), 'baziche-builds', row.apkKey, 600);
  return c.redirect(url, 302);
});
