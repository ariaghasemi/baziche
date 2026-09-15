import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, newId, nowSec } from '../lib/errors';
import { parse, zId } from '../lib/validate';
import { requireAuth } from '../middleware/auth';
import { GAME_TYPE_IDS, TIER1_IDS, GAME_TYPES } from '../registries/game-types';
import { storageRouter } from '../lib/storage-router';
import { getText, putText } from '../lib/r2';
import { threeWayMerge } from '../lib/merge';
import { audit } from '../lib/audit';

export const projectRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();
projectRoutes.use('*', requireAuth);

const MAX_PROJECTS_FREE = 50;

interface OwnedProject {
  id: string;
  uid: string;
  name: string;
  gameType: string;
  shardId: string;
  rev: number;
  status: string;
}

function blankProjectJson(name: string, gameType: string, targetApi: number, minApi: number) {
  const gt = GAME_TYPES.find((g) => g.id === gameType);
  return {
    formatVersion: 1,
    meta: { name, gameType, versionCode: 1, versionName: '1.0.0', orientation: gt?.orientation ?? 'portrait', templateId: null },
    settings: { locale: 'fa', fps: 60 },
    scenes: [{ id: 'scene_main', name: 'Main', entry: true, background: {}, objectIds: [], transitions: [] }],
    objects: [],
    components: [],
    events: [],
    variables: [],
    assets: [],
    audio: {},
    animations: [],
    levels: [],
    ui: {},
    systems: {},
    monetization: {},
    build: { applicationId: '', appName: { fa: name, en: name }, versionCode: 1, versionName: '1.0.0', targetApi, minApi, signing: 'platform-managed' },
  };
}

// Schema-lite validation (full JSON Schema enforced in Phase 2 editor + build validation).
function validateProjectJson(json: unknown, maxBytes: number): { name?: string } {
  const text = JSON.stringify(json ?? null);
  if (text.length > maxBytes) throw err('INVALID_PROJECT_JSON', 'Project JSON too large', 400);
  if (typeof json !== 'object' || json === null) throw err('INVALID_PROJECT_JSON', 'Project JSON must be object', 400);
  const j = json as Record<string, unknown>;
  if (j.formatVersion !== 1) throw err('INVALID_PROJECT_JSON', 'Unsupported formatVersion (expected 1)', 400);
  for (const k of ['meta', 'settings', 'scenes', 'objects', 'events', 'variables', 'assets']) {
    if (!(k in j)) throw err('INVALID_PROJECT_JSON', `Missing key: ${k}`, 400);
  }
  if (!Array.isArray(j.scenes) || !Array.isArray(j.objects)) throw err('INVALID_PROJECT_JSON', 'scenes/objects must be arrays', 400);
  if (j.objects.length > 5000 || j.scenes.length > 200) throw err('INVALID_PROJECT_JSON', 'Project too large', 400);
  const meta = j.meta as Record<string, unknown>;
  if (typeof meta?.name === 'string' && meta.name.length > 80) throw err('INVALID_PROJECT_JSON', 'meta.name too long', 400);
  return { name: typeof meta?.name === 'string' ? meta.name : undefined };
}

function revKey(shardId: string, projectId: string, rev: number): string {
  return storageRouter.keyFor({ id: shardId } as never, 'project', shardId, projectId, `r${rev}.json`);
}

async function ownProject(db: D1Database, userId: string, id: string): Promise<OwnedProject> {
  const p = await db
    .prepare('SELECT id, user_id AS uid, name, game_type AS gameType, shard_id AS shardId, rev, status FROM projects WHERE id = ?')
    .bind(id)
    .first<OwnedProject>();
  if (!p || p.uid !== userId) throw err('PROJECT_NOT_FOUND', 'Project not found', 404);
  if (p.status !== 'active') throw err('PROJECT_DELETED', 'Project deleted', 410);
  return p;
}

async function getRevJson(env: Env, p: OwnedProject, rev: number): Promise<unknown | null> {
  const text = await getText(env.R2_PROJECTS, revKey(p.shardId, p.id, rev));
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** Write json as a new revision guarded by expectedRev. Returns new rev or throws 409. */
async function writeNewRev(
  env: Env,
  p: OwnedProject,
  expectedRev: number,
  json: unknown,
  name: string | undefined,
  action: string,
  userId: string,
): Promise<number> {
  const maxBytes = parseInt(env.MAX_PROJECT_JSON_BYTES || '5242880', 10);
  const { name: metaName } = validateProjectJson(json, maxBytes);
  const newRev = p.rev + 1;
  const text = JSON.stringify(json);
  await putText(env.R2_PROJECTS, revKey(p.shardId, p.id, newRev), text);
  const now = nowSec();
  const finalName = name ?? metaName ?? p.name;
  const r = await env.DB_DATA.prepare('UPDATE projects SET rev = ?, name = ?, updated_at = ? WHERE id = ? AND rev = ?')
    .bind(newRev, finalName, now, p.id, expectedRev)
    .run();
  if ((r.meta.changes ?? 0) !== 1) {
    return -1; // concurrent write lost
  }
  await env.DB_DATA.prepare('INSERT INTO project_revisions (id, project_id, rev, r2_key, bytes, created_at) VALUES (?, ?, ?, ?, ?, ?)')
    .bind(newId('rev'), p.id, newRev, revKey(p.shardId, p.id, newRev), text.length, now)
    .run();
  await audit(env.DB_AUTH, action, userId, { projectId: p.id, rev: newRev });
  return newRev;
}

projectRoutes.get('/', async (c) => {
  const userId = c.get('userId');
  const rows = await c.env.DB_DATA.prepare(
    `SELECT id, name, game_type AS gameType, format_version AS formatVersion, rev, status,
            created_at AS createdAt, updated_at AS updatedAt
     FROM projects WHERE user_id = ? AND status = 'active' ORDER BY updated_at DESC LIMIT 200`,
  )
    .bind(userId)
    .all();
  return c.json({ success: true, projects: rows.results ?? [] });
});

projectRoutes.post('/', async (c) => {
  const userId = c.get('userId');
  const b = parse(z.object({ name: z.string().min(1).max(80), gameType: z.string().min(1).max(32) }), await c.req.json());
  if (!GAME_TYPE_IDS.has(b.gameType)) throw err('UNKNOWN_GAME_TYPE', 'Unknown game type', 400);

  const cnt = await c.env.DB_DATA.prepare("SELECT COUNT(*) AS n FROM projects WHERE user_id = ? AND status = 'active'")
    .bind(userId)
    .first<{ n: number }>();
  if ((cnt?.n ?? 0) >= MAX_PROJECTS_FREE) throw err('PROJECT_LIMIT_REACHED', 'Project limit reached', 403);

  const shard = await storageRouter.resolveShard(c.env.DB_DATA, 'project');
  const id = newId('prj');
  const now = nowSec();
  const targetApi = parseInt(c.env.DEFAULT_TARGET_API || '36', 10);
  const minApi = parseInt(c.env.DEFAULT_MIN_API || '26', 10);
  const json = blankProjectJson(b.name, b.gameType, targetApi, minApi);
  const key = storageRouter.keyFor(shard, 'project', shard.id, id, 'r1.json');
  await putText(c.env.R2_PROJECTS, key, JSON.stringify(json));

  await c.env.DB_DATA.prepare(
    `INSERT INTO projects (id, user_id, name, game_type, format_version, shard_id, rev, status, created_at, updated_at)
     VALUES (?, ?, ?, ?, 1, ?, 1, 'active', ?, ?)`,
  )
    .bind(id, userId, b.name, b.gameType, shard.id, now, now)
    .run();
  await c.env.DB_DATA.prepare('INSERT INTO project_revisions (id, project_id, rev, r2_key, bytes, created_at) VALUES (?, ?, 1, ?, ?, ?)')
    .bind(newId('rev'), id, key, JSON.stringify(json).length, now)
    .run();
  await audit(c.env.DB_AUTH, 'project.create', userId, { projectId: id, gameType: b.gameType });
  return c.json({ success: true, project: { id, name: b.name, gameType: b.gameType, rev: 1, tier1: TIER1_IDS.includes(b.gameType) } });
});

projectRoutes.get('/:id', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const p = await ownProject(c.env.DB_DATA, userId, id);
  const json = await getRevJson(c.env, p, p.rev);
  return c.json({
    success: true,
    project: { id: p.id, name: p.name, gameType: p.gameType, rev: p.rev },
    json,
  });
});

projectRoutes.patch('/:id', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const b = parse(z.object({ baseRev: z.number().int().min(1), name: z.string().min(1).max(80).optional(), json: z.unknown() }), await c.req.json());
  const p = await ownProject(c.env.DB_DATA, userId, id);
  if (b.baseRev !== p.rev) {
    const serverCopy = await getRevJson(c.env, p, p.rev);
    return c.json(
      { success: false, error: { code: 'REVISION_CONFLICT', message: 'Base revision is stale' }, serverRev: p.rev, serverCopy },
      409,
    );
  }
  const newRev = await writeNewRev(c.env, p, p.rev, b.json, b.name, 'project.save', userId);
  if (newRev < 0) {
    return c.json({ success: false, error: { code: 'REVISION_CONFLICT', message: 'Concurrent write, retry' }, serverRev: p.rev + 1 }, 409);
  }
  return c.json({ success: true, rev: newRev });
});

projectRoutes.delete('/:id', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  await ownProject(c.env.DB_DATA, userId, id);
  await c.env.DB_DATA.prepare("UPDATE projects SET status = 'deleted', updated_at = ? WHERE id = ?").bind(nowSec(), id).run();
  await audit(c.env.DB_AUTH, 'project.delete', userId, { projectId: id });
  return c.json({ success: true });
});

projectRoutes.get('/:id/revisions', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const p = await c.env.DB_DATA.prepare('SELECT id, user_id AS uid FROM projects WHERE id = ?').bind(id).first<{ id: string; uid: string }>();
  if (!p || p.uid !== userId) throw err('PROJECT_NOT_FOUND', 'Project not found', 404);
  const rows = await c.env.DB_DATA.prepare('SELECT rev, bytes, created_at AS createdAt FROM project_revisions WHERE project_id = ? ORDER BY rev DESC LIMIT 100')
    .bind(id)
    .all();
  return c.json({ success: true, revisions: rows.results ?? [] });
});

// POST /:id/restore {rev, baseRev} — restore an old revision as a NEW revision (history preserved).
projectRoutes.post('/:id/restore', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const b = parse(z.object({ rev: z.number().int().min(1), baseRev: z.number().int().min(1) }), await c.req.json());
  const p = await ownProject(c.env.DB_DATA, userId, id);
  if (b.baseRev !== p.rev) {
    return c.json({ success: false, error: { code: 'REVISION_CONFLICT', message: 'Base revision is stale' }, serverRev: p.rev }, 409);
  }
  if (b.rev >= p.rev) throw err('VALIDATION_ERROR', 'Can only restore older revisions', 400);
  const oldJson = await getRevJson(c.env, p, b.rev);
  if (!oldJson) throw err('VALIDATION_ERROR', 'Revision content not found', 400);
  const newRev = await writeNewRev(c.env, p, p.rev, oldJson, undefined, 'project.restore', userId);
  if (newRev < 0) {
    return c.json({ success: false, error: { code: 'REVISION_CONFLICT', message: 'Concurrent write, retry' }, serverRev: p.rev + 1 }, 409);
  }
  return c.json({ success: true, rev: newRev, restoredFrom: b.rev });
});

// POST /:id/merge {baseRev, json} — three-way auto-merge (mine vs server against base).
projectRoutes.post('/:id/merge', async (c) => {
  const userId = c.get('userId');
  const id = parse(zId, c.req.param('id'));
  const b = parse(z.object({ baseRev: z.number().int().min(1), json: z.unknown() }), await c.req.json());
  const p = await ownProject(c.env.DB_DATA, userId, id);
  const maxBytes = parseInt(c.env.MAX_PROJECT_JSON_BYTES || '5242880', 10);
  validateProjectJson(b.json, maxBytes);
  const base = await getRevJson(c.env, p, b.baseRev);
  if (!base) throw err('VALIDATION_ERROR', 'Base revision not found', 400);
  const theirs = await getRevJson(c.env, p, p.rev);
  if (!theirs) throw err('INTERNAL', 'Current revision missing', 500);
  const { merged, conflicts } = threeWayMerge(base, b.json, theirs);
  await audit(c.env.DB_AUTH, 'project.merge', userId, { projectId: id, baseRev: b.baseRev, conflicts: conflicts.length });
  return c.json({ success: true, merged, conflicts, serverRev: p.rev });
});
