import { describe, it, expect, afterEach, vi } from 'vitest';
import { makeCtx, req, registerUser } from './setup';

afterEach(() => vi.unstubAllGlobals());

const VALID_PROJECT = {
  formatVersion: 1,
  meta: { name: 'T', gameType: 'quiz', orientation: 'portrait' },
  settings: { locale: 'fa', fps: 60 },
  scenes: [{ id: 's1', name: 'M', entry: true, background: {}, objectIds: [], transitions: [] }],
  objects: [],
  events: [],
  variables: [],
  assets: [],
};

function chatCompletion(content: string, status = 200) {
  return vi.fn(async (_url: string, _init?: { body?: unknown }) =>
    new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status }),
  );
}

describe('POST /api/v1/ai/expand', () => {
  it('501s without an API key', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const res = await req(ctx, '/api/v1/ai/expand', { method: 'POST', token: u.accessToken, body: { prompt: 'space quiz' } });
    expect(res.status).toBe(501);
  });

  it('expands via the provider and validates the project', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).AI_API_KEY = 'sk_test';
    const f = chatCompletion(JSON.stringify(VALID_PROJECT));
    vi.stubGlobal('fetch', f);
    const res = await req(ctx, '/api/v1/ai/expand', { method: 'POST', token: u.accessToken, body: { prompt: 'space quiz', seed: 11 } });
    expect(res.status).toBe(200);
    const j = (await res.json()) as { json: { formatVersion: number }; seed: number; model: string };
    expect(j.json.formatVersion).toBe(1);
    expect(j.seed).toBe(11);
    expect(j.model).toBe('gpt-4o-mini');
    expect(f).toHaveBeenCalledTimes(1);
    const [url, init] = f.mock.calls[0];
    expect(url).toBe('https://api.openai.com/v1/chat/completions');
    const body = JSON.parse((init?.body ?? '{}') as string) as { response_format: { type: string }; messages: { role: string; content: string }[] };
    expect(body.response_format.type).toBe('json_object');
    expect(body.messages[1].content).toContain('space quiz');
    const used = ctx.dbAuth.exec(`SELECT count FROM ai_usage WHERE user_id = '${u.user.id}'`)[0]?.values[0]?.[0];
    expect(used).toBe(1);
  });

  it('honors AI_BASE_URL / AI_MODEL overrides', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const e = ctx.env as unknown as Record<string, string>;
    e.AI_API_KEY = 'sk_test';
    e.AI_BASE_URL = 'https://proxy.example.com/v1/';
    e.AI_MODEL = 'custom-model';
    const f = chatCompletion(JSON.stringify(VALID_PROJECT));
    vi.stubGlobal('fetch', f);
    const res = await req(ctx, '/api/v1/ai/expand', { method: 'POST', token: u.accessToken, body: { prompt: 'x' } });
    expect(res.status).toBe(200);
    expect(f.mock.calls[0][0]).toBe('https://proxy.example.com/v1/chat/completions');
  });

  it('502s on upstream errors and garbage', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).AI_API_KEY = 'sk_test';
    vi.stubGlobal('fetch', vi.fn(async () => new Response('bad', { status: 500 })));
    let res = await req(ctx, '/api/v1/ai/expand', { method: 'POST', token: u.accessToken, body: { prompt: 'x' } });
    expect(res.status).toBe(502);
    vi.stubGlobal('fetch', chatCompletion('not json{{'));
    res = await req(ctx, '/api/v1/ai/expand', { method: 'POST', token: u.accessToken, body: { prompt: 'x' } });
    expect(res.status).toBe(502);
    const j = (await res.json()) as { error: { code: string } };
    expect(j.error.code).toBe('AI_UPSTREAM_ERROR');
  });

  it('rejects model output that is not buildable', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).AI_API_KEY = 'sk_test';
    vi.stubGlobal('fetch', chatCompletion(JSON.stringify({ formatVersion: 1, scenes: [{ id: 's', entry: false }], objects: [], events: [] })));
    const res = await req(ctx, '/api/v1/ai/expand', { method: 'POST', token: u.accessToken, body: { prompt: 'x' } });
    expect(res.status).toBe(400);
    const j = (await res.json()) as { error: { code: string } };
    expect(j.error.code).toBe('BUILD_VALIDATION_FAILED');
  });

  it('enforces the hourly quota', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).AI_API_KEY = 'sk_test';
    vi.stubGlobal('fetch', chatCompletion(JSON.stringify(VALID_PROJECT)));
    const hour = Math.floor(Date.now() / 1000 / 3600);
    ctx.dbAuth.exec(`INSERT INTO ai_usage (user_id, hour, count) VALUES ('${u.user.id}', ${hour}, 20)`);
    const res = await req(ctx, '/api/v1/ai/expand', { method: 'POST', token: u.accessToken, body: { prompt: 'x' } });
    expect(res.status).toBe(429);
  });
});
