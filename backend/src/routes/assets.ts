import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, newId, nowSec } from '../lib/errors';
import { parse, zHex } from '../lib/validate';
import { requireAuth } from '../middleware/auth';
import { storageRouter } from '../lib/storage-router';
import { presignPutUrl, type R2Creds } from '../lib/r2';
import { kindAllows, sniff } from '../lib/magic';
import { audit } from '../lib/audit';

export const assetRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();
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

assetRoutes.post('/presign', async (c) => {
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
    accountId: c.env.R2_ACCOUNT_ID,
    accessKeyId: c.env.R2_ACCESS_KEY_ID,
    secretAccessKey: c.env.R2_SECRET_ACCESS_KEY,
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
  const userId = c.get('userId');
  const b = parse(commitSchema, await c.req.json());
  const p = await c.env.DB_DATA.prepare('SELECT id, user_id AS uid FROM projects WHERE id = ?')
    .bind(b.projectId)
    .first<{ id: string; uid: string }>();
  if (!p || p.uid !== userId) throw err('PROJECT_NOT_FOUND', 'Project not found', 404);
  if (!b.key.startsWith('assets/') || b.key.includes('..')) throw err('VALIDATION_ERROR', 'Bad key', 400);

  const head = await c.env.R2_ASSETS.get(b.key, { range: { offset: 0, length: 32 } });
  if (!head) throw err('ASSET_NOT_FOUND', 'Upload not found, presign again', 404);

  // Magic-byte verification: content must match the declared kind.
  const buf = new Uint8Array(await head.arrayBuffer());
  const sniffed = sniff(buf);
  if (!kindAllows(b.kind, sniffed)) {
    throw err('ASSET_TYPE_BLOCKED', `Content is ${sniffed}, expected ${b.kind}`, 400);
  }

  const id = newId('ast');
  await c.env.DB_DATA.prepare(
    'INSERT INTO assets (id, project_id, kind, hash, r2_key, bytes, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
  )
    .bind(id, b.projectId, b.kind, b.hash.toLowerCase(), b.key, b.bytes, nowSec())
    .run();
  await audit(c.env.DB_AUTH, 'asset.commit', userId, { assetId: id, kind: b.kind, bytes: b.bytes });
  return c.json({ success: true, asset: { id, key: b.key } });
});
