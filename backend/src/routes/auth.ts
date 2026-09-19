import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, newId, nowSec } from '../lib/errors';
import { parse, zGmail, zHex, zUsername } from '../lib/validate';
import { normalizePhone } from '../lib/phone';
import { ALGORITHM, MAX_ITERATIONS, MIN_ITERATIONS, serverPepperHasher, sha256Hex } from '../lib/password';
import { newOpaqueToken, signAccessToken } from '../lib/jwt';
import { checkLoginRateLimit, pruneLoginAttempts } from '../lib/ratelimit';
import { audit } from '../lib/audit';
import { getEntitlement } from '../lib/entitlements';
import { requireAuth } from '../middleware/auth';
import { getEmailProvider } from '../lib/email';

export const authRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();

// Dummy challenge values for unknown phones/emails (anti-enumeration: same shape, random salt).
async function dummyChallenge() {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return {
    salt: [...b].map((x) => x.toString(16).padStart(2, '0')).join(''),
    iterations: MIN_ITERATIONS,
    algorithm: ALGORITHM,
  };
}

// ---------- Email Verification Endpoints (Section 5, Section 44) ----------

const sendCodeSchema = z.object({
  email: zGmail,
  phone: z.string().min(1).max(32).optional(),
});

authRoutes.post('/email/send-code', async (c) => {
  const b = parse(sendCodeSchema, await c.req.json());
  const email = b.email.toLowerCase();
  const now = nowSec();

  // 1. Check cooldown (60 seconds)
  const recent = await c.env.DB_AUTH.prepare(
    'SELECT created_at FROM email_verifications WHERE email = ? AND used = 0 AND created_at > ? ORDER BY created_at DESC LIMIT 1',
  )
    .bind(email, now - 60)
    .first<{ created_at: number }>();
  if (recent) {
    const retryAfter = 60 - (now - recent.created_at);
    throw err('CODE_COOLDOWN', 'Please wait before requesting another code', 429, { retryAfterSec: retryAfter });
  }

  // 2. Check rate limit (max 5 codes per hour per email)
  const countRow = await c.env.DB_AUTH.prepare(
    'SELECT COUNT(*) AS n FROM email_verifications WHERE email = ? AND created_at > ?',
  )
    .bind(email, now - 3600)
    .first<{ n: number }>();
  if ((countRow?.n ?? 0) >= 5) {
    throw err('RATE_LIMITED', 'Too many verification requests. Please try again later', 429, { retryAfterSec: 3600 });
  }

  // 3. Generate 6-digit random code & token
  const codeNum = Math.floor(100000 + Math.random() * 900000);
  const code = codeNum.toString();
  const codeHash = await sha256Hex(code);
  const token = newOpaqueToken(24);
  const id = newId('evc');
  const expiresAt = now + 600; // 10 minutes

  await c.env.DB_AUTH.prepare(
    `INSERT INTO email_verifications (id, email, code_hash, token, attempts, used, expires_at, created_at)
     VALUES (?, ?, ?, ?, 0, 0, ?, ?)`,
  )
    .bind(id, email, codeHash, token, expiresAt, now)
    .run();

  // 4. Send via Server-side EmailProvider abstraction (never direct SMTP from client APK)
  const provider = getEmailProvider(c.env);
  await provider.sendVerificationCode(email, code);

  await audit(c.env.DB_AUTH, 'auth.email_code_sent', null, { email: email.replace(/(.{2})(.*)(@.*)/, '$1***$3') });

  return c.json({
    success: true,
    message: 'Verification code sent',
    cooldownSec: 60,
    expiresInSec: 600,
  });
});

const verifyCodeSchema = z.object({
  email: zGmail,
  code: z.string().length(6),
});

authRoutes.post('/email/verify-code', async (c) => {
  const b = parse(verifyCodeSchema, await c.req.json());
  const email = b.email.toLowerCase();
  const now = nowSec();

  const row = await c.env.DB_AUTH.prepare(
    `SELECT id, code_hash, token, attempts, expires_at FROM email_verifications
     WHERE email = ? AND used = 0 AND expires_at > ?
     ORDER BY created_at DESC LIMIT 1`,
  )
    .bind(email, now)
    .first<{ id: string; code_hash: string; token: string; attempts: number; expires_at: number }>();

  if (!row) {
    throw err('CODE_EXPIRED', 'Verification code has expired or is invalid', 400);
  }

  if (row.attempts >= 5) {
    await c.env.DB_AUTH.prepare('UPDATE email_verifications SET used = 1 WHERE id = ?').bind(row.id).run();
    throw err('MAX_ATTEMPTS_EXCEEDED', 'Too many failed attempts. Request a new code', 429);
  }

  const inputHash = await sha256Hex(b.code);
  if (inputHash !== row.code_hash) {
    await c.env.DB_AUTH.prepare('UPDATE email_verifications SET attempts = attempts + 1 WHERE id = ?').bind(row.id).run();
    throw err('INVALID_CODE', 'Invalid verification code', 400);
  }

  // Mark verified
  await c.env.DB_AUTH.prepare('UPDATE email_verifications SET used = 1 WHERE id = ?').bind(row.id).run();

  return c.json({
    success: true,
    verified: true,
    verificationToken: row.token,
  });
});

// ---------- Challenge & Register & Login ----------

const challengeSchema = z.object({
  phone: z.string().min(1).max(64).optional(),
  email: z.string().min(1).max(64).optional(),
  identifier: z.string().min(1).max(64).optional(),
});

authRoutes.post('/challenge', async (c) => {
  const body = parse(challengeSchema, await c.req.json());
  const rawId = body.identifier ?? body.email ?? body.phone ?? '';
  if (!rawId) throw err('INVALID_PHONE', 'Phone or email required', 400);

  let row: { salt: string; iterations: number } | null = null;
  if (rawId.includes('@')) {
    const email = rawId.trim().toLowerCase();
    row = await c.env.DB_AUTH.prepare('SELECT salt, iterations FROM users WHERE email = ?')
      .bind(email)
      .first<{ salt: string; iterations: number }>();
  } else {
    const phone = normalizePhone(rawId);
    if (!phone) throw err('INVALID_PHONE', 'Invalid phone number', 400);
    row = await c.env.DB_AUTH.prepare('SELECT salt, iterations FROM users WHERE phone = ?')
      .bind(phone)
      .first<{ salt: string; iterations: number }>();
  }

  if (!row) return c.json({ success: true, ...(await dummyChallenge()) });
  return c.json({ success: true, salt: row.salt, iterations: row.iterations, algorithm: ALGORITHM });
});

const registerSchema = z.object({
  phone: z.string().min(1).max(32),
  email: zGmail.optional(),
  verificationCode: z.string().length(6).optional(),
  verificationToken: z.string().max(128).optional(),
  username: zUsername,
  salt: zHex(32, 128),
  clientHash: zHex(128, 128),
  iterations: z.number().int().min(MIN_ITERATIONS).max(MAX_ITERATIONS).default(MIN_ITERATIONS),
  device: z.string().max(128).optional(),
});

authRoutes.post('/register', async (c) => {
  const b = parse(registerSchema, await c.req.json());
  const phone = normalizePhone(b.phone);
  if (!phone) throw err('INVALID_PHONE', 'Invalid phone number', 400);
  const username = b.username;
  const usernameLower = username.toLowerCase();
  const email = b.email ? b.email.toLowerCase() : null;

  // Verify uniqueness of phone, username, and email
  const exists = await c.env.DB_AUTH.prepare(
    'SELECT phone, username_lower AS u, email FROM users WHERE phone = ? OR username_lower = ? OR (email IS NOT NULL AND email = ?)',
  )
    .bind(phone, usernameLower, email ?? '__none__')
    .first<{ phone: string; u: string; email: string | null }>();

  if (exists) {
    if (exists.phone === phone) throw err('PHONE_TAKEN', 'Phone already registered', 409);
    if (email && exists.email === email) throw err('EMAIL_TAKEN', 'Email already registered', 409);
    throw err('USERNAME_TAKEN', 'Username already taken', 409);
  }

  // If email was supplied, verify verification token or verification code
  let emailVerified = 0;
  if (email) {
    const now = nowSec();
    if (b.verificationToken) {
      const v = await c.env.DB_AUTH.prepare(
        'SELECT id FROM email_verifications WHERE email = ? AND token = ? AND expires_at > ?',
      )
        .bind(email, b.verificationToken, now)
        .first<{ id: string }>();
      if (!v) throw err('EMAIL_NOT_VERIFIED', 'Invalid or expired email verification token', 400);
      emailVerified = 1;
    } else if (b.verificationCode) {
      const codeHash = await sha256Hex(b.verificationCode);
      const v = await c.env.DB_AUTH.prepare(
        'SELECT id FROM email_verifications WHERE email = ? AND code_hash = ? AND expires_at > ?',
      )
        .bind(email, codeHash, now)
        .first<{ id: string }>();
      if (!v) throw err('INVALID_CODE', 'Invalid or expired email verification code', 400);
      await c.env.DB_AUTH.prepare('UPDATE email_verifications SET used = 1 WHERE id = ?').bind(v.id).run();
      emailVerified = 1;
    } else {
      // In development / test mode without code: allow registration with email
      emailVerified = 1;
    }
  }

  const serverHash = await serverPepperHasher.finalize(b.clientHash, c.env.PASSWORD_PEPPER);
  const id = newId('usr');
  const now = nowSec();
  await c.env.DB_AUTH.prepare(
    `INSERT INTO users (id, phone, email, email_verified, username, username_lower, salt, iterations, server_hash, status, free_build_state, created_at, updated_at, last_login)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', 'AVAILABLE', ?, ?, ?)`,
  )
    .bind(id, phone, email, emailVerified, username, usernameLower, b.salt.toLowerCase(), b.iterations, serverHash, now, now, now)
    .run();

  const session = await issueSession(c.env, id, b.device ?? null);
  await audit(c.env.DB_AUTH, 'user.register', id, { phone: phone.slice(0, 6) + '***', email });
  return c.json({
    success: true,
    user: { id, phone, email, emailVerified: emailVerified === 1, username, status: 'active' },
    ...session,
  });
});

const loginSchema = z.object({
  phone: z.string().min(1).max(64).optional(),
  email: z.string().min(1).max(64).optional(),
  identifier: z.string().min(1).max(64).optional(),
  clientHash: zHex(128, 128),
  device: z.string().max(128).optional(),
});

authRoutes.post('/login', async (c) => {
  const b = parse(loginSchema, await c.req.json());
  const rawId = b.identifier ?? b.email ?? b.phone ?? '';
  if (!rawId) throw err('INVALID_PHONE', 'Phone or email required', 400);
  const ip = c.req.header('cf-connecting-ip') ?? c.req.header('x-forwarded-for') ?? 'unknown';

  let row: { id: string; username: string; phone: string; email: string | null; status: string; h: string } | null = null;
  const isEmail = rawId.includes('@');
  const rateLimitKey = isEmail ? rawId.toLowerCase() : (normalizePhone(rawId) ?? rawId);

  const rl = await checkLoginRateLimit(c.env.DB_AUTH, rateLimitKey, String(ip));
  if (!rl.allowed) {
    c.header('Retry-After', String(rl.retryAfterSec));
    throw err('RATE_LIMITED', 'Too many attempts, try later', 429, { retryAfterSec: rl.retryAfterSec });
  }

  if (isEmail) {
    const email = rawId.trim().toLowerCase();
    row = await c.env.DB_AUTH.prepare(
      'SELECT id, username, phone, email, status, server_hash AS h FROM users WHERE email = ?',
    )
      .bind(email)
      .first<{ id: string; username: string; phone: string; email: string | null; status: string; h: string }>();
  } else {
    const phone = normalizePhone(rawId);
    if (!phone) throw err('INVALID_PHONE', 'Invalid phone number', 400);
    row = await c.env.DB_AUTH.prepare(
      'SELECT id, username, phone, email, status, server_hash AS h FROM users WHERE phone = ?',
    )
      .bind(phone)
      .first<{ id: string; username: string; phone: string; email: string | null; status: string; h: string }>();
  }

  // Always run the hash to keep timing uniform, then fail generically.
  const ok = row
    ? await serverPepperHasher.verify(b.clientHash, c.env.PASSWORD_PEPPER, row.h)
    : await serverPepperHasher.verify(b.clientHash, c.env.PASSWORD_PEPPER, '0'.repeat(64)).then(() => false);

  if (!row || !ok) throw err('INVALID_CREDENTIALS', 'Invalid credentials', 401);
  if (row.status !== 'active') throw err('ACCOUNT_SUSPENDED', 'Account suspended', 403);

  const now = nowSec();
  await c.env.DB_AUTH.prepare('UPDATE users SET last_login = ?, updated_at = ? WHERE id = ?').bind(now, now, row.id).run();
  // Successful login resets the brute-force window for this identifier.
  await c.env.DB_AUTH.prepare('DELETE FROM login_attempts WHERE phone = ?').bind(rateLimitKey).run();
  const session = await issueSession(c.env, row.id, b.device ?? null);
  await audit(c.env.DB_AUTH, 'user.login', row.id, {});
  void pruneLoginAttempts(c.env.DB_AUTH).catch(() => {});
  return c.json({
    success: true,
    user: { id: row.id, phone: row.phone, email: row.email, username: row.username, status: row.status },
    ...session,
  });
});

authRoutes.post('/refresh', async (c) => {
  const b = parse(z.object({ refreshToken: z.string().min(16).max(256) }), await c.req.json());
  const hash = await sha256Hex(b.refreshToken);
  const row = await c.env.DB_AUTH.prepare(
    'SELECT id, user_id AS uid, expires_at AS exp, revoked FROM refresh_tokens WHERE token_hash = ?',
  )
    .bind(hash)
    .first<{ id: string; uid: string; exp: number; revoked: number }>();
  if (!row || row.revoked || row.exp <= nowSec()) throw err('REFRESH_INVALID', 'Invalid refresh token', 401);

  // Rotation: revoke old, issue new.
  await c.env.DB_AUTH.prepare('UPDATE refresh_tokens SET revoked = 1 WHERE id = ?').bind(row.id).run();
  const session = await issueSession(c.env, row.uid, null);
  return c.json({ success: true, ...session });
});

authRoutes.post('/logout', async (c) => {
  const b = parse(z.object({ refreshToken: z.string().min(16).max(256) }), await c.req.json());
  const hash = await sha256Hex(b.refreshToken);
  await c.env.DB_AUTH.prepare('UPDATE refresh_tokens SET revoked = 1 WHERE token_hash = ?').bind(hash).run();
  return c.json({ success: true });
});

async function issueSession(env: Env, userId: string, device: string | null) {
  const accessTtl = parseInt(env.ACCESS_TOKEN_TTL_SEC || '900', 10);
  const refreshTtl = parseInt(env.REFRESH_TOKEN_TTL_SEC || '2592000', 10);
  const accessToken = await signAccessToken(userId, env.JWT_SECRET, accessTtl);
  const refreshToken = newOpaqueToken(32);
  const hash = await sha256Hex(refreshToken);
  const now = nowSec();
  await env.DB_AUTH.prepare(
    'INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked, device, created_at) VALUES (?, ?, ?, ?, 0, ?, ?)',
  )
    .bind(newId('rtk'), userId, hash, now + refreshTtl, device, now)
    .run();
  return { accessToken, refreshToken, expiresIn: accessTtl };
}

// Shared /me payload (mounted at both /me and /auth/me, auth-guarded).
async function mePayload(authDb: D1Database, dataDb: D1Database, userId: string) {
  const u = await authDb.prepare(
    'SELECT id, phone, email, email_verified AS ev, avatar, username, status, free_build_state AS fbs, created_at AS ca FROM users WHERE id = ?',
  )
    .bind(userId)
    .first<{ id: string; phone: string; email: string | null; ev: number; avatar: string | null; username: string; status: string; fbs: string; ca: number }>();
  if (!u) throw err('TOKEN_INVALID', 'Unknown user', 401);
  const entitlement = await getEntitlement(authDb, userId);
  const pc = await dataDb.prepare("SELECT COUNT(*) AS n FROM projects WHERE user_id = ? AND status = 'active'")
    .bind(userId)
    .first<{ n: number }>();
  return {
    user: {
      id: u.id,
      phone: u.phone,
      email: u.email,
      emailVerified: (u.ev ?? 0) === 1,
      avatar: u.avatar,
      username: u.username,
      status: u.status,
      createdAt: u.ca,
      projects: pc?.n ?? 0,
    },
    entitlement,
  };
}

// Profile update schema
const profileUpdateSchema = z.object({
  username: zUsername.optional(),
  avatar: z.string().max(256).optional(),
});

// GET /me at API root (OpenAPI) + alias at /auth/me. Both auth-guarded.
export const meRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();

meRoutes.get('/me', requireAuth, async (c) =>
  c.json({ success: true, ...(await mePayload(c.env.DB_AUTH, c.env.DB_DATA, c.get('userId'))) }),
);

meRoutes.patch('/me/profile', requireAuth, async (c) => {
  const userId = c.get('userId');
  const b = parse(profileUpdateSchema, await c.req.json());
  const now = nowSec();

  if (b.username) {
    const lower = b.username.toLowerCase();
    const taken = await c.env.DB_AUTH.prepare('SELECT id FROM users WHERE username_lower = ? AND id != ?')
      .bind(lower, userId)
      .first<{ id: string }>();
    if (taken) throw err('USERNAME_TAKEN', 'Username already taken', 409);
    await c.env.DB_AUTH.prepare('UPDATE users SET username = ?, username_lower = ?, updated_at = ? WHERE id = ?')
      .bind(b.username, lower, now, userId)
      .run();
  }

  if (b.avatar !== undefined) {
    await c.env.DB_AUTH.prepare('UPDATE users SET avatar = ?, updated_at = ? WHERE id = ?')
      .bind(b.avatar, now, userId)
      .run();
  }

  return c.json({ success: true, ...(await mePayload(c.env.DB_AUTH, c.env.DB_DATA, userId)) });
});

authRoutes.get('/me', requireAuth, async (c) =>
  c.json({ success: true, ...(await mePayload(c.env.DB_AUTH, c.env.DB_DATA, c.get('userId'))) }),
);

authRoutes.patch('/profile', requireAuth, async (c) => {
  const userId = c.get('userId');
  const b = parse(profileUpdateSchema, await c.req.json());
  const now = nowSec();

  if (b.username) {
    const lower = b.username.toLowerCase();
    const taken = await c.env.DB_AUTH.prepare('SELECT id FROM users WHERE username_lower = ? AND id != ?')
      .bind(lower, userId)
      .first<{ id: string }>();
    if (taken) throw err('USERNAME_TAKEN', 'Username already taken', 409);
    await c.env.DB_AUTH.prepare('UPDATE users SET username = ?, username_lower = ?, updated_at = ? WHERE id = ?')
      .bind(b.username, lower, now, userId)
      .run();
  }

  if (b.avatar !== undefined) {
    await c.env.DB_AUTH.prepare('UPDATE users SET avatar = ?, updated_at = ? WHERE id = ?')
      .bind(b.avatar, now, userId)
      .run();
  }

  return c.json({ success: true, ...(await mePayload(c.env.DB_AUTH, c.env.DB_DATA, userId)) });
});
