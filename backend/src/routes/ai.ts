import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, nowSec } from '../lib/errors';
import { parse } from '../lib/validate';
import { requireAuth } from '../middleware/auth';
import { assertBuildable } from './builds';
import { audit } from '../lib/audit';

export const aiRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();
aiRoutes.use('*', requireAuth);

const AI_HOURLY_LIMIT = 20;
const DEFAULT_MODEL = 'gpt-4o-mini';
const DEFAULT_BASE = 'https://api.openai.com/v1';

const SYSTEM_PROMPT = `You expand a one-line game idea into a Baziche project JSON.
Output ONLY JSON (no markdown, no commentary) with this exact shape:
{"formatVersion":1,"meta":{"name":string,"gameType":string,"orientation":"portrait"|"landscape"},
"settings":{"locale":"fa","fps":60},
"scenes":[{"id":string,"name":string,"entry":boolean,"background":{"color":"#rrggbb"},"objectIds":[string],"transitions":[]}],
"objects":[{"id":string,"sceneId":string,"kind":"rect"|"circle"|"text"|"button"|"sprite","visible":true,"layer":0,
"components":{"Transform":{"x":n,"y":n,"w":n,"h":n},"Text":{"text":string,"size":n,"color":"#rrggbb"}|optional,
"Button":{"label":string,"eventId":string,"enabled":true}|optional}}],
"events":[{"id":string,"trigger":{"type":"start"|"tap"|"timer","target"?:string},"actions":[{"capability":"CAP-0001".."CAP-0032","params":{}}]}],
"variables":[{"name":string,"type":"number"|"string"|"boolean","initial":0|""|false}],
"assets":[],"audio":{},"animations":[],"levels":[],"ui":{},"systems":{},"monetization":{},
"build":{"applicationId":"","appName":{"fa":string,"en":string},"versionCode":1,"versionName":"1.0.0","targetApi":36,"minApi":26,"signing":"platform-managed"}}
Rules: portrait 480x800, landscape 800x480. Exactly one scene with entry=true.
Every object.sceneId must exist. Every objectIds entry must exist. Only CAP-0001..CAP-0032.
Keep it small (<=3 scenes, <=20 objects).`;

// POST /api/v1/ai/expand — generic OpenAI-compatible expansion. 501 without a key.
aiRoutes.post('/expand', async (c) => {
  const userId = c.get('userId');
  const b = parse(
    z.object({
      prompt: z.string().min(1).max(2000),
      gameType: z.string().min(1).max(32).default('quiz'),
      seed: z.number().int().min(0).max(2147483647).optional(),
      maxTokens: z.number().int().min(256).max(4000).default(2500),
    }),
    await c.req.json(),
  );
  if (!c.env.AI_API_KEY) throw err('NOT_IMPLEMENTED', 'AI expansion is not configured on this server', 501);

  // Hourly quota (attempts count, even if the upstream fails — abuse protection).
  const now = nowSec();
  const hour = Math.floor(now / 3600);
  const used = await c.env.DB_AUTH.prepare('SELECT count AS n FROM ai_usage WHERE user_id = ? AND hour = ?')
    .bind(userId, hour)
    .first<{ n: number }>();
  if ((used?.n ?? 0) >= AI_HOURLY_LIMIT) throw err('RATE_LIMITED', 'AI hourly limit reached, try later', 429);
  await c.env.DB_AUTH.prepare(
    `INSERT INTO ai_usage (user_id, hour, count) VALUES (?, ?, 1)
     ON CONFLICT(user_id, hour) DO UPDATE SET count = count + 1`,
  )
    .bind(userId, hour)
    .run();

  const seed = b.seed ?? Math.floor(Math.random() * 2147483647);
  const base = (c.env.AI_BASE_URL || DEFAULT_BASE).replace(/\/+$/, '');
  const model = c.env.AI_MODEL || DEFAULT_MODEL;
  let res: Response;
  try {
    res = await fetch(`${base}/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${c.env.AI_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model,
        temperature: 0.7,
        max_tokens: b.maxTokens,
        response_format: { type: 'json_object' },
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: `gameType=${b.gameType} seed=${seed}\nIdea: ${b.prompt}` },
        ],
      }),
      signal: AbortSignal.timeout(45000),
    });
  } catch {
    throw err('AI_UPSTREAM_ERROR', 'AI provider unreachable', 502);
  }
  if (!res.ok) throw err('AI_UPSTREAM_ERROR', `AI provider error ${res.status}`, 502);
  let outer: unknown;
  try {
    outer = await res.json();
  } catch {
    throw err('AI_UPSTREAM_ERROR', 'AI provider returned invalid JSON', 502);
  }
  const content = (outer as { choices?: { message?: { content?: string } }[] })?.choices?.[0]?.message?.content;
  if (typeof content !== 'string' || !content) throw err('AI_UPSTREAM_ERROR', 'AI provider returned no content', 502);
  let inner: unknown;
  try {
    inner = JSON.parse(content);
  } catch {
    throw err('AI_UPSTREAM_ERROR', 'AI output was not valid project JSON', 502);
  }
  assertBuildable(inner); // throws BUILD_VALIDATION_FAILED when the model drifts
  await audit(c.env.DB_AUTH, 'ai.expand', userId, { gameType: b.gameType, seed, model });
  return c.json({ success: true, json: inner, seed, model });
});
