import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, newId, nowSec } from '../lib/errors';
import { parse, zHex } from '../lib/validate';
import { requireAuth } from '../middleware/auth';
import { storageRouter } from '../lib/storage-router';
import { presignPutUrl, type R2Creds } from '../lib/r2';
import { backendOfRow, storageBackendOf, storageFor, storageForBackend, type BinaryRef } from '../lib/binary-storage';
import { verifyContentToken } from '../lib/content-token';
import { kindAllows, sniff } from '../lib/magic';
import { sha256Hex } from '../lib/bundle';
import { audit } from '../lib/audit';

export const assetRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();

// NOTE: /content is mounted BEFORE requireAuth (public bearer-URL download, same
// threat model as a presigned URL — HMAC token + short expiry instead of auth).
const CONTENT_PATH = '/api/v1/assets/content';

assetRoutes.get('/content', async (c) => {
  const key = c.req.query('key') ?? '';
  const exp = Number(c.req.query('exp') ?? '');
  const token = c.req.query('token') ?? '';
  const ok = await verifyContentToken(c.env.JWT_SECRET, CONTENT_PATH, key, exp, token);
  if (!ok) throw err('TOKEN_INVALID', 'Bad or expired download token', 401);
  if (!key.startsWith('assets/') || key.includes('..')) throw err('ASSET_NOT_FOUND', 'Asset not found', 404);
  const a = await c.env.DB_DATA.prepare(
    `SELECT r2_key AS r2key, storage, release_tag AS releaseTag, release_id AS releaseId,
            asset_id AS assetId, asset_name AS assetName, content_type AS contentType
     FROM assets WHERE r2_key = ?`,
  )
    .bind(key)
    .first<{ r2key: string; storage: string | null; releaseTag: string | null; releaseId: number | null; assetId: number | null; assetName: string | null; contentType: string | null }>();
  if (!a) throw err('ASSET_NOT_FOUND', 'Asset not found', 404);
  const ref: BinaryRef = { key: a.r2key, storage: backendOfRow(a.storage, c.env), releaseTag: a.releaseTag, releaseId: a.releaseId, assetId: a.assetId, assetName: a.assetName };
  const bytes = await storageForBackend(c.env, ref.storage).get(ref);
  if (!bytes) throw err('ASSET_NOT_FOUND', 'Asset bytes missing', 404);
  return new Response(bytes as unknown as BodyInit, {
    headers: {
      'Content-Type': a.contentType || 'application/octet-stream',
      'Content-Length': String(bytes.byteLength),
      'Cache-Control': 'private, max-age=600',
    },
  });
});

assetRoutes.use('*', requireAuth);

// Per-kind allowlist + size caps (MVP).
const KIND_RULES: Record<string, { mimes: string[]; maxBytes: number }> = {
  image: { mimes: ['image/png', 'image/jpeg', 'image/webp'], maxBytes: 10 * 1024 * 1024 },
  audio: { mimes: ['audio/mpeg', 'audio/ogg', 'audio/wav', 'audio/mp4'], maxBytes: 20 * 1024 * 1024 },
  font: { mimes: ['font/ttf', 'font/otf', 'font/woff', 'font/woff2'], maxBytes: 5 * 1024 * 1024 },
  video: { mimes: ['video/mp4'], maxBytes: 50 * 1024 * 1024 },
};

const presignSchema = z.object({
  projectId: z.string().min(1).max(80),
  kind: z.enum(['image', 'audio', 'font', 'video']),
  hash: zHex(64, 64),
  bytes: z.number().int().min(1),
  contentType: z.string().min(1).max(128),
});

// Direct-to-R2 upload handshake (r2 mode only). In github mode uploads go
// through POST /upload (single call: Worker verifies + stores + commits).
assetRoutes.post('/presign', async (c) => {
  if (storageBackendOf(c.env) !== 'r2') {
    throw err('STORAGE_MODE', 'Direct upload is unavailable in github storage mode, use POST /assets/upload', 400);
  }
  const userId = c.get('userId');
  const b = parse(presignSchema, await c.req.json());
  const p = await c.env.DB_DATA.prepare('SELECT id, user_id AS uid, shard_id AS shardId FROM projects WHERE id = ?')
    .bind(b.projectId)
    .first<{ id: string; uid: string; shardId: string }>();
  if (!p || p.uid !== userId) throw err('PROJECT_NOT_FOUND', 'Project not found', 404);

  const rule = KIND_RULES[b.kind];
  if (!rule.mimes.includes(b.contentType)) throw err('ASSET_TYPE_BLOCKED', 'MIME not allowed for kind', 400);
  if (b.bytes > rule.maxBytes) throw err('ASSET_TOO_LARGE', 'Asset too large', 413);

  const shard = await storageRouter.resolveShard(c.env.DB_DATA, 'asset');
  const key = storageRouter.keyFor(shard, 'asset', shard.id, b.projectId, `${b.hash.slice(0, 16)}-${newId('a')}`);
  const creds: R2Creds = {
    accountId: c.env.R2_ACCOUNT_ID ?? '',
    accessKeyId: c.env.R2_ACCESS_KEY_ID ?? '',
    secretAccessKey: c.env.R2_SECRET_ACCESS_KEY ?? '',
  };
  const uploadUrl = await presignPutUrl(creds, 'baziche-assets', key, b.contentType, 900);
  return c.json({ success: true, key, uploadUrl, expiresIn: 900 });
});

const commitSchema = z.object({
  projectId: z.string().min(1).max(80),
  key: z.string().min(1).max(512),
  hash: zHex(64, 64),
  bytes: z.number().int().min(1),
  kind: z.enum(['image', 'audio', 'font', 'video']),
});

assetRoutes.post('/commit', async (c) => {
  if (storageBackendOf(c.env) !== 'r2') {
    throw err('STORAGE_MODE', 'Direct upload is unavailable in github storage mode, use POST /assets/upload', 400);
  }
  const userId = c.get('userId');
  const b = parse(commitSchema, await c.req.json());
  const p = await c.env.DB_DATA.prepare('SELECT id, user_id AS uid FROM projects WHERE id = ?')
    .bind(b.projectId)
    .first<{ id: string; uid: string }>();
  if (!p || p.uid !== userId) throw err('PROJECT_NOT_FOUND', 'Project not found', 404);
  if (!b.key.startsWith('assets/') || b.key.includes('..')) throw err('VALIDATION_ERROR', 'Bad key', 400);

  const storage = storageFor(c.env);
  const head = await storage.head({ key: b.key, storage: 'r2' });
  if (!head) throw err('ASSET_NOT_FOUND', 'Upload not found, presign again', 404);

  // Magic-byte verification: content must match the declared kind.
  const bytes = await storage.get({ key: b.key, storage: 'r2' });
  if (!bytes) throw err('ASSET_NOT_FOUND', 'Upload not found, presign again', 404);
  const sniffed = sniff(bytes.subarray(0, 32));
  if (!kindAllows(b.kind, sniffed)) {
    throw err('ASSET_TYPE_BLOCKED', `Content is ${sniffed}, expected ${b.kind}`, 400);
  }

  const id = newId('ast');
  await c.env.DB_DATA.prepare(
    'INSERT INTO assets (id, project_id, kind, hash, r2_key, bytes, created_at, storage) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
  )
    .bind(id, b.projectId, b.kind, b.hash.toLowerCase(), b.key, b.bytes, nowSec(), 'r2')
    .run();
  await audit(c.env.DB_AUTH, 'asset.commit', userId, { assetId: id, kind: b.kind, bytes: b.bytes });
  return c.json({ success: true, asset: { id, key: b.key } });
});

// Single-call upload (BOTH backends): multipart {projectId, kind, file}.
// Worker verifies MIME + size + magic bytes, stores via BinaryStorage, commits.
assetRoutes.post('/upload', async (c) => {
  const userId = c.get('userId');
  const form = await c.req.parseBody();
  const projectId = String(form['projectId'] ?? '');
  const kind = String(form['kind'] ?? '');
  const file = form['file'];
  if (!projectId) throw err('VALIDATION_ERROR', 'projectId required', 400);
  const rule = KIND_RULES[kind];
  if (!rule) throw err('VALIDATION_ERROR', 'kind must be image|audio|font|video', 400);
  if (!(file instanceof File)) throw err('VALIDATION_ERROR', 'file required (multipart)', 400);

  const p = await c.env.DB_DATA.prepare('SELECT id, user_id AS uid FROM projects WHERE id = ?')
    .bind(projectId)
    .first<{ id: string; uid: string }>();
  if (!p || p.uid !== userId) throw err('PROJECT_NOT_FOUND', 'Project not found', 404);

  const contentType = file.type || 'application/octet-stream';
  if (!rule.mimes.includes(contentType)) throw err('ASSET_TYPE_BLOCKED', 'MIME not allowed for kind', 400);
  if (file.size < 1) throw err('VALIDATION_ERROR', 'file is empty', 400);
  if (file.size > rule.maxBytes) throw err('ASSET_TOO_LARGE', 'Asset too large', 413);

  const bytes = new Uint8Array(await file.arrayBuffer());
  const sniffed = sniff(bytes.subarray(0, 32));
  if (!kindAllows(kind, sniffed)) {
    throw err('ASSET_TYPE_BLOCKED', `Content is ${sniffed}, expected ${kind}`, 400);
  }
  const hash = await sha256Hex(bytes);

  const shard = await storageRouter.resolveShard(c.env.DB_DATA, 'asset');
  const key = storageRouter.keyFor(shard, 'asset', shard.id, projectId, `${hash.slice(0, 16)}-${newId('a')}`);
  const storage = storageFor(c.env, '', shard.backend ?? null);
  const ref = await storage.put('asset', key, bytes, contentType);

  const id = newId('ast');
  await c.env.DB_DATA.prepare(
    `INSERT INTO assets (id, project_id, kind, hash, r2_key, bytes, created_at,
       storage, release_tag, release_id, asset_id, asset_name, sha256, content_type)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(id, projectId, kind, hash, ref.key, bytes.byteLength, nowSec(), ref.storage, ref.releaseTag ?? null, ref.releaseId ?? null, ref.assetId ?? null, ref.assetName ?? null, ref.sha256 ?? null, contentType)
    .run();
  await audit(c.env.DB_AUTH, 'asset.upload', userId, { assetId: id, kind, bytes: bytes.byteLength });
  return c.json({ success: true, asset: { id, key: ref.key } });
});

// Short-lived download URL for committed assets (Preview audio/images, game-shell builds).
// r2 mode: presigned R2 GET. github mode: HMAC Worker proxy URL (plain GET, no auth).
assetRoutes.get('/:id/url', async (c) => {
  const userId = c.get('userId');
  const id = c.req.param('id');
  const a = await c.env.DB_DATA.prepare(
    `SELECT a.r2_key AS r2key, a.kind AS kind, a.storage AS storage,
            a.release_tag AS releaseTag, a.release_id AS releaseId,
            a.asset_id AS assetId, a.asset_name AS assetName, p.user_id AS uid
     FROM assets a JOIN projects p ON p.id = a.project_id WHERE a.id = ?`,
  )
    .bind(id)
    .first<{ r2key: string; kind: string; storage: string | null; releaseTag: string | null; releaseId: number | null; assetId: number | null; assetName: string | null; uid: string }>();
  if (!a || a.uid !== userId) throw err('ASSET_NOT_FOUND', 'Asset not found', 404);
  const ref: BinaryRef = { key: a.r2key, storage: backendOfRow(a.storage, c.env), releaseTag: a.releaseTag, releaseId: a.releaseId, assetId: a.assetId, assetName: a.assetName };
  const origin = new URL(c.req.url).origin;
  const url = await storageForBackend(c.env, ref.storage, origin).getDownloadUrl(ref, 3600);
  return c.json({ success: true, url, kind: a.kind, expiresIn: 3600 });
});

// List committed assets of one project (owner only). Used by Preview to map
// project-JSON asset ids (by hash) to downloadable server asset ids.
assetRoutes.get('/', async (c) => {
  const userId = c.get('userId');
  const projectId = c.req.query('projectId') ?? '';
  if (!projectId) throw err('VALIDATION_ERROR', 'projectId required', 400);
  const p = await c.env.DB_DATA.prepare('SELECT user_id AS uid FROM projects WHERE id = ?')
    .bind(projectId)
    .first<{ uid: string }>();
  if (!p || p.uid !== userId) throw err('PROJECT_NOT_FOUND', 'Project not found', 404);
  const rows = await c.env.DB_DATA.prepare(
    'SELECT id, kind, hash, bytes, created_at AS createdAt FROM assets WHERE project_id = ? ORDER BY created_at ASC',
  )
    .bind(projectId)
    .all<{ id: string; kind: string; hash: string; bytes: number; createdAt: number }>();
  return c.json({ success: true, assets: rows.results });
});
