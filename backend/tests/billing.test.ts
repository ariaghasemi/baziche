import { describe, it, expect, afterEach, vi } from 'vitest';
import { makeCtx, req, registerUser, createProject } from './setup';

afterEach(() => vi.unstubAllGlobals());

function myketOk() {
  return vi.fn(async (_url: string) =>
    new Response(JSON.stringify({ kind: 'androidpublisher#productPurchase', purchaseTime: 1492839267000, developerPayload: '', purchaseState: 0, consumptionState: 1 }), { status: 200 }),
  );
}

function scalar(ctx: Awaited<ReturnType<typeof makeCtx>>, db: 'auth' | 'data', sql: string): unknown {
  const out = (db === 'auth' ? ctx.dbAuth : ctx.dbData).exec(sql);
  return out[0]?.values[0]?.[0] ?? null;
}

async function buildAndToken(ctx: Awaited<ReturnType<typeof makeCtx>>, accessToken: string, projectId: string) {
  const res = await req(ctx, '/api/v1/builds', { method: 'POST', token: accessToken, body: { projectId } });
  const j = (await res.json()) as { build?: { id: string; status: string }; error?: { code: string } };
  return { status: res.status, build: j.build, error: j.error };
}

async function callback(ctx: Awaited<ReturnType<typeof makeCtx>>, buildId: string, status: string) {
  const token = scalar(ctx, 'data', `SELECT callback_token FROM builds WHERE id = '${buildId}'`) as string | null;
  // QUEUED builds (no dispatch) have no callback token yet: mint the test path via direct update.
  const t = token ?? 'test-cb-token';
  if (!token) ctx.dbData.exec(`UPDATE builds SET callback_token = '${t}' WHERE id = '${buildId}'`);
  return ctx.app.request(
    `/api/v1/builds/${buildId}/callback`,
    { method: 'POST', headers: { authorization: `Bearer ${t}`, 'content-type': 'application/json' }, body: JSON.stringify({ status, runId: 't1' }) },
    ctx.env,
  );
}

describe('POST /api/v1/billing/verify (Myket)', () => {
  it('501s when Myket is not configured', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const res = await req(ctx, '/api/v1/billing/verify', { method: 'POST', token: u.accessToken, body: { packageName: 'com.x', sku: 'sub_monthly', token: 't1' } });
    expect(res.status).toBe(501);
  });

  it('verifies via Myket and grants a subscription', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).MYKET_ACCESS_TOKEN = 'myket_test';
    const f = myketOk();
    vi.stubGlobal('fetch', f);
    const res = await req(ctx, '/api/v1/billing/verify', { method: 'POST', token: u.accessToken, body: { packageName: 'com.x', sku: 'sub_monthly', token: 'tok_sub_1' } });
    expect(res.status).toBe(200);
    const j = (await res.json()) as { granted: { kind: string; planId: string } };
    expect(j.granted).toEqual({ kind: 'subscription', planId: 'MONTHLY', expiresAt: expect.any(Number) });
    expect(f).toHaveBeenCalledTimes(1);
    const calledUrl = (f.mock.calls[0][0] as string);
    expect(calledUrl).toBe('https://developer.myket.ir/api/partners/applications/com.x/purchases/products/sub_monthly/verify');
    expect(scalar(ctx, 'auth', `SELECT plan_id FROM subscriptions WHERE user_id = '${u.user.id}'`)).toBe('MONTHLY');
    // replay same token -> 409, no second Myket call
    const r2 = await req(ctx, '/api/v1/billing/verify', { method: 'POST', token: u.accessToken, body: { packageName: 'com.x', sku: 'sub_monthly', token: 'tok_sub_1' } });
    expect(r2.status).toBe(409);
    expect(f).toHaveBeenCalledTimes(1);
  });

  it('rejects Myket-declined purchases (402 PURCHASE_INVALID)', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).MYKET_ACCESS_TOKEN = 'myket_test';
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ purchaseState: 1 }), { status: 200 })));
    const res = await req(ctx, '/api/v1/billing/verify', { method: 'POST', token: u.accessToken, body: { packageName: 'com.x', sku: 'sub_monthly', token: 'tok_bad' } });
    expect(res.status).toBe(402);
    const j = (await res.json()) as { error: { code: string } };
    expect(j.error.code).toBe('PURCHASE_INVALID');
  });

  it('rejects unknown SKUs without calling Myket', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).MYKET_ACCESS_TOKEN = 'myket_test';
    const f = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', f);
    const res = await req(ctx, '/api/v1/billing/verify', { method: 'POST', token: u.accessToken, body: { packageName: 'com.x', sku: 'nope', token: 't9' } });
    expect(res.status).toBe(400);
    expect(f).not.toHaveBeenCalled();
  });

  it('grants one-shot build_single entitlements', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).MYKET_ACCESS_TOKEN = 'myket_test';
    vi.stubGlobal('fetch', myketOk());
    const res = await req(ctx, '/api/v1/billing/verify', { method: 'POST', token: u.accessToken, body: { packageName: 'com.x', sku: 'build_single', token: 'tok_one_1' } });
    expect(res.status).toBe(200);
    expect(scalar(ctx, 'auth', `SELECT COUNT(*) FROM entitlements WHERE user_id = '${u.user.id}' AND type = 'build_single'`)).toBe(1);
  });
});

describe('free-build gate on POST /builds', () => {
  it('first build claims free; second is RACE_LOST while in flight; COMPLETED consumes; third is 403', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    const b1 = await buildAndToken(ctx, u.accessToken, pid);
    expect(b1.status).toBe(200);
    expect(scalar(ctx, 'auth', `SELECT free_build_state FROM users WHERE id = '${u.user.id}'`)).toBe('RESERVED');
    const b2 = await buildAndToken(ctx, u.accessToken, pid);
    expect(b2.status).toBe(409);
    expect(b2.error?.code).toBe('FREE_BUILD_RACE_LOST');
    // complete b1 (needs the APK in R2 first)
    await ctx.r2builds.put(`builds/${b1.build!.id}/game.apk`, new Uint8Array([1, 2]));
    const cb = await callback(ctx, b1.build!.id, 'COMPLETED');
    expect(((await cb.json()) as { status: string }).status).toBe('COMPLETED');
    expect(scalar(ctx, 'auth', `SELECT free_build_state FROM users WHERE id = '${u.user.id}'`)).toBe('CONSUMED');
    const b3 = await buildAndToken(ctx, u.accessToken, pid);
    expect(b3.status).toBe(403);
    expect(b3.error?.code).toBe('FREE_BUILD_UNAVAILABLE');
  });

  it('FAILED releases the free build (refund)', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    const b1 = await buildAndToken(ctx, u.accessToken, pid);
    expect(b1.status).toBe(200);
    await callback(ctx, b1.build!.id, 'FAILED');
    expect(scalar(ctx, 'auth', `SELECT free_build_state FROM users WHERE id = '${u.user.id}'`)).toBe('AVAILABLE');
    const b2 = await buildAndToken(ctx, u.accessToken, pid);
    expect(b2.status).toBe(200);
  });

  it('one-shot entitlement pays before the free build', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).MYKET_ACCESS_TOKEN = 'myket_test';
    vi.stubGlobal('fetch', myketOk());
    await req(ctx, '/api/v1/billing/verify', { method: 'POST', token: u.accessToken, body: { packageName: 'com.x', sku: 'build_single', token: 'tok_one_2' } });
    const pid = await createProject(ctx, u.accessToken);
    const b1 = await buildAndToken(ctx, u.accessToken, pid);
    expect(b1.status).toBe(200);
    expect(scalar(ctx, 'data', `SELECT spent FROM builds WHERE id = '${b1.build!.id}'`)).toBe('oneshot');
    expect(scalar(ctx, 'auth', `SELECT free_build_state FROM users WHERE id = '${u.user.id}'`)).toBe('AVAILABLE');
  });

  it('subscribers build without touching the free build', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    (ctx.env as unknown as Record<string, string>).MYKET_ACCESS_TOKEN = 'myket_test';
    vi.stubGlobal('fetch', myketOk());
    await req(ctx, '/api/v1/billing/verify', { method: 'POST', token: u.accessToken, body: { packageName: 'com.x', sku: 'sub_lifetime', token: 'tok_sub_9' } });
    const pid = await createProject(ctx, u.accessToken);
    const b1 = await buildAndToken(ctx, u.accessToken, pid);
    const b2 = await buildAndToken(ctx, u.accessToken, pid);
    expect(b1.status).toBe(200);
    expect(b2.status).toBe(200);
    expect(scalar(ctx, 'auth', `SELECT free_build_state FROM users WHERE id = '${u.user.id}'`)).toBe('AVAILABLE');
  });
});

describe('GET /admin/ui', () => {
  it('401 without token, 403 for non-admin, 200 HTML for admin', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    let res = await req(ctx, '/admin/ui');
    expect(res.status).toBe(401);
    res = await req(ctx, `/admin/ui?token=${u.accessToken}`);
    expect(res.status).toBe(403);
    ctx.dbAuth.exec(`INSERT INTO admins (user_id, role, created_at) VALUES ('${u.user.id}', 'admin', 1)`);
    res = await req(ctx, `/admin/ui?token=${u.accessToken}`);
    expect(res.status).toBe(200);
    const html = await res.text();
    expect(html).toContain('مدیریت بازیچه');
    expect(html).toContain(u.user.id);
  });
});
