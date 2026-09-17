// HMAC-signed bearer URLs for authless binary downloads.
//
// Why: Preview (and curl inside game-build) download bytes with a plain GET and
// no Authorization header. In `r2` mode the presigned R2 URL plays this role; in
// `github` mode the PAT must never leave the Worker, so the Worker mints its own
// short-lived signed URL pointing at a Worker proxy route (/assets/content,
// /builds/content). Same threat model as a presigned URL: bearer, expiry-bound.
//
// The signing secret is JWT_SECRET (domain-separated payload, never a JWT).

function toHex(b: ArrayBuffer): string {
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

async function hmacHex(secret: string, data: string): Promise<string> {
  const k = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  return toHex(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(data)));
}

/** Mint a token for (path, key, exp). `path` is the proxy route path. */
export async function signContentToken(secret: string, path: string, key: string, exp: number): Promise<string> {
  if (!secret) throw new Error('signing secret missing');
  if (!Number.isInteger(exp) || exp <= 0) throw new Error('bad exp');
  return hmacHex(secret, `baziche-content-v1\n${path}\n${key}\n${exp}`);
}

/** Constant-time verify + expiry check. Never throws on attacker input. */
export async function verifyContentToken(
  secret: string,
  path: string,
  key: string,
  exp: number,
  token: string,
  nowSec = Math.floor(Date.now() / 1000),
): Promise<boolean> {
  try {
    if (!secret || !token || !Number.isInteger(exp) || exp <= nowSec) return false;
    const want = await signContentToken(secret, path, key, exp);
    if (want.length !== token.length) return false;
    let diff = 0;
    for (let i = 0; i < want.length; i++) diff |= want.charCodeAt(i) ^ token.charCodeAt(i);
    return diff === 0;
  } catch {
    return false;
  }
}
