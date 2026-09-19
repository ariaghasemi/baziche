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

  it('email verification: send-code -> rejects non-gmail -> cooldown -> verify -> register with email -> login with email', async () => {
    const ctx = await makeCtx();

    // Rejects non-gmail
    const nonGmail = await req(ctx, '/api/v1/auth/email/send-code', {
      method: 'POST',
      body: { email: 'user@yahoo.com' },
    });
    expect(nonGmail.status).toBe(400);

    // Valid Gmail sends code
    const send = await req(ctx, '/api/v1/auth/email/send-code', {
      method: 'POST',
      body: { email: 'player1@gmail.com' },
    });
    expect(send.status).toBe(200);
    const sendJson = (await send.json()) as { success: boolean; cooldownSec: number };
    expect(sendJson.success).toBe(true);
    expect(sendJson.cooldownSec).toBe(60);

    // Cooldown immediate retry
    const retryImmediate = await req(ctx, '/api/v1/auth/email/send-code', {
      method: 'POST',
      body: { email: 'player1@gmail.com' },
    });
    expect(retryImmediate.status).toBe(429);

    // Retrieve generated code from test DB to simulate user receiving the email
    const row = await ctx.env.DB_AUTH.prepare(
      'SELECT token FROM email_verifications WHERE email = ?',
    )
      .bind('player1@gmail.com')
      .first<{ token: string }>();
    expect(row).not.toBeNull();

    // Wrong code fails
    const badVerify = await req(ctx, '/api/v1/auth/email/verify-code', {
      method: 'POST',
      body: { email: 'player1@gmail.com', code: '000000' },
    });
    expect(badVerify.status).toBe(400);

    // Register with verification token
    const salt = randomSaltHex();
    const clientHash = await clientStretch('StrongGmailPass123', salt, 100000);
    const regRes = await req(ctx, '/api/v1/auth/register', {
      method: 'POST',
      body: {
        phone: '09127777777',
        email: 'player1@gmail.com',
        verificationToken: row!.token,
        username: 'gmailuser',
        salt,
        clientHash,
        iterations: 100000,
      },
    });
    expect(regRes.status).toBe(200);
    const regJson = (await regRes.json()) as { success: boolean; user: { email: string; emailVerified: boolean } };
    expect(regJson.user.email).toBe('player1@gmail.com');
    expect(regJson.user.emailVerified).toBe(true);

    // Challenge with email
    const ch = await req(ctx, '/api/v1/auth/challenge', {
      method: 'POST',
      body: { email: 'player1@gmail.com' },
    });
    expect(ch.status).toBe(200);
    const chj = (await ch.json()) as { salt: string; iterations: number };
    expect(chj.salt).toBe(salt);

    // Login with email
    const login = await req(ctx, '/api/v1/auth/login', {
      method: 'POST',
      body: {
        email: 'player1@gmail.com',
        clientHash: await clientStretch('StrongGmailPass123', chj.salt, chj.iterations),
      },
    });
    expect(login.status).toBe(200);
    const loginJson = (await login.json()) as { accessToken: string; user: { email: string } };
    expect(loginJson.user.email).toBe('player1@gmail.com');

    // /me includes email and emailVerified
    const me = await req(ctx, '/api/v1/me', { token: loginJson.accessToken });
    const mej = (await me.json()) as { user: { email: string; emailVerified: boolean; username: string } };
    expect(mej.user.email).toBe('player1@gmail.com');
    expect(mej.user.emailVerified).toBe(true);

    // PATCH /me/profile updates username and avatar
    const patchRes = await req(ctx, '/api/v1/me/profile', {
      method: 'PATCH',
      token: loginJson.accessToken,
      body: { username: 'gmailuser_pro', avatar: 'avatar_gamer_01' },
    });
    expect(patchRes.status).toBe(200);
    const patchJson = (await patchRes.json()) as { user: { username: string; avatar: string } };
    expect(patchJson.user.username).toBe('gmailuser_pro');
    expect(patchJson.user.avatar).toBe('avatar_gamer_01');
  });
});
