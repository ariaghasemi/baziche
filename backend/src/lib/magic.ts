// Magic-byte sniffing for uploaded assets (checked on /assets/commit).
// Declared kind must match actual content; mismatches are rejected.

export type Sniffed =
  | 'png' | 'jpeg' | 'webp' | 'mp3' | 'ogg' | 'wav' | 'mp4'
  | 'ttf' | 'otf' | 'woff' | 'woff2' | 'unknown';

function starts(b: Uint8Array, ...bytes: number[]): boolean {
  return bytes.every((x, i) => b[i] === x);
}

function ascii(b: Uint8Array, off: number, s: string): boolean {
  for (let i = 0; i < s.length; i++) if (b[off + i] !== s.charCodeAt(i)) return false;
  return true;
}

export function sniff(b: Uint8Array): Sniffed {
  if (b.length < 12) return 'unknown';
  if (starts(b, 0x89, 0x50, 0x4e, 0x47)) return 'png';
  if (starts(b, 0xff, 0xd8, 0xff)) return 'jpeg';
  if (ascii(b, 0, 'RIFF') && ascii(b, 8, 'WEBP')) return 'webp';
  if (ascii(b, 0, 'RIFF') && ascii(b, 8, 'WAVE')) return 'wav';
  if (ascii(b, 0, 'ID3')) return 'mp3';
  if (starts(b, 0xff, 0xfb) || starts(b, 0xff, 0xf3) || starts(b, 0xff, 0xf2)) return 'mp3';
  if (ascii(b, 0, 'OggS')) return 'ogg';
  if (ascii(b, 4, 'ftyp')) return 'mp4';
  if (starts(b, 0x00, 0x01, 0x00, 0x00)) return 'ttf';
  if (ascii(b, 0, 'OTTO')) return 'otf';
  if (ascii(b, 0, 'wOFF')) return 'woff';
  if (ascii(b, 0, 'wOF2')) return 'woff2';
  return 'unknown';
}

const KIND_MAP: Record<string, Sniffed[]> = {
  image: ['png', 'jpeg', 'webp'],
  audio: ['mp3', 'ogg', 'wav', 'mp4'],
  font: ['ttf', 'otf', 'woff', 'woff2'],
  video: ['mp4'],
};

export function kindAllows(kind: string, s: Sniffed): boolean {
  return (KIND_MAP[kind] ?? []).includes(s);
}
