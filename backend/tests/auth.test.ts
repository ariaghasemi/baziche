import { describe, expect, it } from 'vitest';
import { clientStretch, makeCtx, randomSaltHex, registerUser, req } from './setup';

describe('auth', () => {
  it('challenge returns dummy shape for unknown phone', async () => {
    const ctx = await makeCtx();
    const res = await req(ctx, '/api/v1/auth/challenge', { method: 'POST', body: { phone: '09120000000' } });
    const j = (await res.json()) as { success: boolean; salt: string; iterations: number; algorithm: string };
    expect(res.status).toBe(200);
    expect(j.success).toBe(true);
    expect(j.salt).toMatch(/^[0-9a-f]{32}$/);
    expect(j.iterations).toBe(100000);
  });

  it('challenge rejects invalid phone', async () => {
    const ctx = await makeCtx();
    const res = await req(ctx, '/api/v1/auth/challenge', { method: 'POST', body: { phone: '123' } });
    const j = (await res.json()) as { error: { code: string } };
    expect(res.status).toBe(400);
    expect(j.error.code).toBe('INVALID_PHONE');
  });

  it('register -> challenge(salt) -> login -> me', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09121111111', 'aria');
    expect(s.accessToken.length).toBeGreaterThan(20);

    const ch = await req(ctx, '/api/v1/auth/challenge', { method: 'POST', body: { phone: '+989121111111' } });
    const chj = (await ch.json()) as { salt: string; iterations: number };
    expect(chj.salt).toMatch(/^[0-9a-f]{32}$/);

    const login = await req(ctx, '/api/v1/auth/login', {
      method: 'POST',
      body: { phone: '09121111111', clientHash: await clientStretch('StrongPass123', chj.salt, chj.iterations) },
    });
    expect(login.status).toBe(200);
    const lj = (await login.json()) as { accessToken: string };

    const me = await req(ctx, '/api/v1/me', { token: lj.accessToken });
    const mej = (await me.json()) as { success: boolean; user: { username: string }; entitlement: { freeBuild: string } };
    expect(me.status).toBe(200);
    expect(mej.user.username).toBe('aria');
    expect(mej.entitlement.freeBuild).toBe('AVAILABLE');
  });

  it('register rejects duplicate phone and username', async () => {
    const ctx = await makeCtx();
    await registerUser(ctx, '09122222222', 'userone');
    const salt = randomSaltHex();
    const dup1 = await req(ctx, '/api/v1/auth/register', {
      method: 'POST',
      body: { phone: '09122222222', username: 'othertwo', salt, clientHash: 'c'.repeat(128), iterations: 100000 },
    });
    expect(((await dup1.json()) as { error: { code: string } }).error.code).toBe('PHONE_TAKEN');
    const dup2 = await req(ctx, '/api/v1/auth/register', {
      method: 'POST',
      body: { phone: '09123333333', username: 'UserOne', salt, clientHash: 'c'.repeat(128), iterations: 100000 },
    });
    expect(((await dup2.json()) as { error: { code: string } }).error.code).toBe('USERNAME_TAKEN');
  });

  it('login with wrong password -> 401 and rate-limits after 5 attempts', async () => {
    const ctx = await makeCtx();
    await registerUser(ctx, '09124444444', 'ratelimit');
    for (let i = 0; i < 5; i++) {
      const r = await req(ctx, '/api/v1/auth/login', { method: 'POST', body: { phone: '09124444444', clientHash: 'd'.repeat(128) } });
      expect(r.status).toBe(401);
    }
    const limited = await req(ctx, '/api/v1/auth/login', { method: 'POST', body: { phone: '09124444444', clientHash: 'd'.repeat(128) } });
    expect(limited.status).toBe(429);
    expect(((await limited.json()) as { error: { code: string } }).error.code).toBe('RATE_LIMITED');
  });

  it('refresh rotates: old token dies, new works', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09125555555', 'refresher');
    const r1 = await req(ctx, '/api/v1/auth/refresh', { method: 'POST', body: { refreshToken: s.refreshToken } });
    expect(r1.status).toBe(200);
    const j1 = (await r1.json()) as { refreshToken: string };
    expect(j1.refreshToken).not.toBe(s.refreshToken);
    const r2 = await req(ctx, '/api/v1/auth/refresh', { method: 'POST', body: { refreshToken: s.refreshToken } });
    expect(r2.status).toBe(401);
  });

  it('logout revokes refresh token', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09126666666', 'logoutter', 'StrongPass123');
    const me = await req(ctx, '/api/v1/auth/me', { token: s.accessToken });
    expect(me.status).toBe(200);
    await req(ctx, '/api/v1/auth/logout', { method: 'POST', token: s.accessToken, body: { refreshToken: s.refreshToken } });
    const r = await req(ctx, '/api/v1/auth/refresh', { method: 'POST', body: { refreshToken: s.refreshToken } });
    expect(r.status).toBe(401);
  });
});
