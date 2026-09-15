// R2 access has two paths:
//  1) Server-side via Workers binding (R2Bucket): small objects (project JSON), HEAD checks.
//  2) Client transfer via S3-compatible SigV4 PRESIGNED URLs (no Cloudflare creds on device).
// The presigner below is hand-rolled with WebCrypto (exact expiry control, zero deps).
// Presigning is pure HMAC — works offline (tests use dummy creds and assert URL structure).

export interface R2Creds {
  accountId: string;
  accessKeyId: string;
  secretAccessKey: string;
}

function toHex(b: ArrayBuffer): string {
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

async function hmac(key: ArrayBuffer | Uint8Array, data: string): Promise<Uint8Array> {
  const k = await crypto.subtle.importKey('raw', key as BufferSource, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(data)));
}

async function sha256HexStr(data: string): Promise<string> {
  return toHex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(data)));
}

function amzDates(now = new Date()): { amzDate: string; dateStamp: string } {
  const p = (n: number) => String(n).padStart(2, '0');
  const y = now.getUTCFullYear();
  const m = p(now.getUTCMonth() + 1);
  const d = p(now.getUTCDate());
  const hh = p(now.getUTCHours());
  const mm = p(now.getUTCMinutes());
  const ss = p(now.getUTCSeconds());
  return { amzDate: `${y}${m}${d}T${hh}${mm}${ss}Z`, dateStamp: `${y}${m}${d}` };
}

export interface PresignOpts {
  method: 'GET' | 'PUT';
  bucket: string;
  key: string;
  expiresSec: number;
  /** for PUT diagnostics only (payload stays UNSIGNED-PAYLOAD like AWS SDK JS v3) */
  contentType?: string;
}

export async function presignS3Url(creds: R2Creds, o: PresignOpts): Promise<string> {
  if (!creds.accountId || !creds.accessKeyId || !creds.secretAccessKey) {
    throw new Error('R2 credentials missing');
  }
  if (o.expiresSec < 1 || o.expiresSec > 604800) throw new Error('bad expires');
  const host = `${creds.accountId}.r2.cloudflarestorage.com`;
  const region = 'auto';
  const service = 's3';
  const { amzDate, dateStamp } = amzDates();
  const credentialScope = `${dateStamp}/${region}/${service}/aws4_request`;
  const canonicalUri = `/${o.bucket}/` + o.key.split('/').map((s) => encodeURIComponent(s)).join('/');

  const params: Record<string, string> = {
    'X-Amz-Algorithm': 'AWS4-HMAC-SHA256',
    'X-Amz-Credential': `${creds.accessKeyId}/${credentialScope}`,
    'X-Amz-Date': amzDate,
    'X-Amz-Expires': String(o.expiresSec),
    'X-Amz-SignedHeaders': 'host',
  };
  const canonicalQuery = Object.keys(params)
    .sort()
    .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(params[k])}`)
    .join('&');
  const canonicalHeaders = `host:${host}\n`;
  const payloadHash = 'UNSIGNED-PAYLOAD';
  const canonicalRequest = [o.method, canonicalUri, canonicalQuery, canonicalHeaders, 'host', payloadHash].join('\n');
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, credentialScope, await sha256HexStr(canonicalRequest)].join('\n');

  const kDate = await hmac(new TextEncoder().encode(`AWS4${creds.secretAccessKey}`), dateStamp);
  const kRegion = await hmac(kDate, region);
  const kService = await hmac(kRegion, service);
  const kSigning = await hmac(kService, 'aws4_request');
  const signature = toHex((await hmac(kSigning, stringToSign)).buffer as ArrayBuffer);

  return `https://${host}${canonicalUri}?${canonicalQuery}&X-Amz-Signature=${signature}`;
}

export async function presignPutUrl(creds: R2Creds, bucket: string, key: string, contentType: string, expiresSec = 900): Promise<string> {
  return presignS3Url(creds, { method: 'PUT', bucket, key, expiresSec, contentType });
}

export async function presignGetUrl(creds: R2Creds, bucket: string, key: string, expiresSec = 3600): Promise<string> {
  return presignS3Url(creds, { method: 'GET', bucket, key, expiresSec });
}

export async function putText(bucket: R2Bucket, key: string, text: string): Promise<void> {
  await bucket.put(key, text, { httpMetadata: { contentType: 'application/json' } });
}

export async function getText(bucket: R2Bucket, key: string): Promise<string | null> {
  const obj = await bucket.get(key);
  if (!obj) return null;
  return await obj.text();
}
