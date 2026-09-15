// ARCHITECTURE DECISION (documented per spec §16):
// Workers Free allows ~10ms CPU/request, so server-side Argon2/bcrypt is NOT viable.
// We use a two-layer scheme:
//   1) Client-Stretch (Android, JDK PBKDF2-HMAC-SHA512, >=100k iterations, per-user salt).
//      The server NEVER sees the raw password.
//   2) Server-Finalize (this file): SHA-256(clientHash || pepper) via WebCrypto.
//      Runs in microseconds, fits free-tier CPU. Pepper lives in Secrets (PASSWORD_PEPPER).
// Security properties:
//   - DB leak alone is useless for login (pepper unknown; hash is preimage-resistant).
//   - Offline cracking of the original password still requires breaking PBKDF2.
// The PasswordHasher interface keeps the door open for server-side Argon2id on Workers Paid
// without changing any API contract.

export const MIN_ITERATIONS = 100_000;
export const MAX_ITERATIONS = 2_000_000;
export const ALGORITHM = 'PBKDF2-HMAC-SHA512';

export interface StretchParams {
  salt: string; // hex
  clientHash: string; // hex, 128 chars (sha512 output)
  iterations: number;
}

export interface PasswordHasher {
  finalize(clientHashHex: string, pepperHex: string): Promise<string>;
  verify(clientHashHex: string, pepperHex: string, expectedHex: string): Promise<boolean>;
}

function hexToBytes(hex: string): Uint8Array {
  if (hex.length % 2 !== 0) throw new Error('bad hex');
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function bytesToHex(b: Uint8Array): string {
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}

function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export const serverPepperHasher: PasswordHasher = {
  async finalize(clientHashHex: string, pepperHex: string): Promise<string> {
    const a = hexToBytes(clientHashHex.toLowerCase());
    const p = hexToBytes(pepperHex.toLowerCase());
    const joined = new Uint8Array(a.length + p.length);
    joined.set(a, 0);
    joined.set(p, a.length);
    const digest = await crypto.subtle.digest('SHA-256', joined);
    return bytesToHex(new Uint8Array(digest));
  },
  async verify(clientHashHex: string, pepperHex: string, expectedHex: string): Promise<boolean> {
    const actual = await this.finalize(clientHashHex, pepperHex);
    return timingSafeEqualHex(actual.toLowerCase(), expectedHex.toLowerCase());
  },
};

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return bytesToHex(new Uint8Array(digest));
}
