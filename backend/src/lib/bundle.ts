// Build bundle: zip (fflate, zero-dep, Workers-safe) + AES-256-CBC/PBKDF2 encryption
// byte-compatible with `openssl enc -d -aes-256-cbc -pbkdf2` (game-build.yml).
// OpenSSL salted format: "Salted__" + 8-byte salt + ciphertext;
// key+IV = PBKDF2-SHA256(password, salt, 10000, 48 bytes).
import { zipSync, unzipSync } from 'fflate';

export async function buildBundleZip(files: Record<string, Uint8Array>): Promise<Uint8Array> {
  return zipSync(files, { level: 6 });
}

export function unzipBundleZip(data: Uint8Array): Record<string, Uint8Array> {
  return unzipSync(data);
}

async function pbkdf2Key(password: string, salt: Uint8Array): Promise<{ key: Uint8Array; iv: Uint8Array }> {
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = new Uint8Array(
    await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations: 10000 }, base, 48 * 8),
  );
  return { key: bits.slice(0, 32), iv: bits.slice(32, 48) };
}

export async function encryptOpensslAes256Cbc(plain: Uint8Array, password: string): Promise<Uint8Array> {
  const salt = crypto.getRandomValues(new Uint8Array(8));
  const { key, iv } = await pbkdf2Key(password, salt);
  const ck = await crypto.subtle.importKey('raw', key as BufferSource, 'AES-CBC', false, ['encrypt']);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CBC', iv: iv as BufferSource }, ck, plain as BufferSource));
  const out = new Uint8Array(16 + ct.length);
  out.set(new TextEncoder().encode('Salted__'), 0);
  out.set(salt, 8);
  out.set(ct, 16);
  return out;
}

export async function decryptOpensslAes256Cbc(enc: Uint8Array, password: string): Promise<Uint8Array> {
  const header = new TextDecoder().decode(enc.slice(0, 8));
  if (header !== 'Salted__') throw new Error('not an openssl salted bundle');
  const salt = enc.slice(8, 16);
  const { key, iv } = await pbkdf2Key(password, salt);
  const ck = await crypto.subtle.importKey('raw', key as BufferSource, 'AES-CBC', false, ['decrypt']);
  const pt = await crypto.subtle.decrypt({ name: 'AES-CBC', iv: iv as BufferSource }, ck, enc.slice(16) as BufferSource);
  return new Uint8Array(pt);
}

export async function sha256Hex(data: Uint8Array): Promise<string> {
  const d = await crypto.subtle.digest('SHA-256', data as BufferSource);
  return [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

export function randomHex(bytes: number): string {
  return [...crypto.getRandomValues(new Uint8Array(bytes))].map((x) => x.toString(16).padStart(2, '0')).join('');
}
