import { nowSec } from './errors';

// D1-backed fixed-window rate limiter (free-tier friendly: 1 read + 1 conditional write).
// Table login_attempts(phone, ip, at) doubles as brute-force evidence.
export async function checkLoginRateLimit(
  db: D1Database,
  phone: string,
  ip: string,
  windowSec = 60,
  maxAttempts = 5,
): Promise<{ allowed: boolean; retryAfterSec: number }> {
  const now = nowSec();
  const since = now - windowSec;
  const row = await db
    .prepare('SELECT COUNT(*) AS n, MIN(at) AS first FROM login_attempts WHERE at > ? AND (phone = ? OR ip = ?)')
    .bind(since, phone, ip)
    .first<{ n: number; first: number | null }>();
  const n = row?.n ?? 0;
  if (n < maxAttempts) {
    await db.prepare('INSERT INTO login_attempts (phone, ip, at) VALUES (?, ?, ?)').bind(phone, ip, now).run();
    return { allowed: true, retryAfterSec: 0 };
  }
  const retryAfter = Math.max(1, (row?.first ?? now) + windowSec - now);
  return { allowed: false, retryAfterSec: retryAfter };
}

// Best-effort cleanup of old attempt rows (called opportunistically, errors ignored by caller).
export async function pruneLoginAttempts(db: D1Database, olderThanSec = 3600): Promise<void> {
  await db.prepare('DELETE FROM login_attempts WHERE at < ?').bind(nowSec() - olderThanSec).run();
}
