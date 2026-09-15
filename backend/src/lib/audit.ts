import { nowSec } from './errors';

// Append-only audit log. NEVER pass passwords/tokens/secrets in meta.
export async function audit(
  db: D1Database,
  action: string,
  userId: string | null,
  meta?: Record<string, unknown>,
): Promise<void> {
  try {
    await db
      .prepare('INSERT INTO audit_logs (at, user_id, action, meta) VALUES (?, ?, ?, ?)')
      .bind(nowSec(), userId, action, meta ? JSON.stringify(meta).slice(0, 2000) : null)
      .run();
  } catch {
    // Audit must never break the request path.
  }
}
