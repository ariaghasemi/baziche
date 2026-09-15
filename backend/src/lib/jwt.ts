import * as jose from 'jose';

// Access tokens: HS256, short-lived (15m default).
// Rotation: verify against JWT_SECRET, fallback to JWT_SECRET_PREV during rotation.
function secretBytes(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

export async function signAccessToken(
  userId: string,
  jwtSecret: string,
  ttlSec: number,
): Promise<string> {
  return await new jose.SignJWT({ sub: userId, typ: 'access' })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuedAt()
    .setExpirationTime(`${ttlSec}s`)
    .sign(secretBytes(jwtSecret));
}

export async function verifyAccessToken(
  token: string,
  jwtSecret: string,
  prevSecret?: string,
): Promise<string> {
  const trySecrets = [jwtSecret, ...(prevSecret ? [prevSecret] : [])];
  let lastErr: unknown = null;
  for (const s of trySecrets) {
    try {
      const { payload } = await jose.jwtVerify(token, secretBytes(s));
      if (payload.typ !== 'access' || typeof payload.sub !== 'string') throw new Error('bad claims');
      return payload.sub;
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr instanceof Error ? lastErr : new Error('invalid token');
}

export function newOpaqueToken(bytes = 32): string {
  const b = new Uint8Array(bytes);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}
