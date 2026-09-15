import { nowSec } from './errors';

// Subscription + Free-Build entitlement logic (server = source of truth).
// Free build state machine: AVAILABLE -> RESERVED -> CONSUMED, with RESERVED -> AVAILABLE on real failure.

export type FreeBuildState = 'AVAILABLE' | 'RESERVED' | 'CONSUMED';

export interface Entitlement {
  subscribed: boolean;
  planId: string;
  expiresAt: number | null;
  freeBuild: FreeBuildState;
  buildsAllowed: boolean;
}

export async function getEntitlement(db: D1Database, userId: string): Promise<Entitlement> {
  const now = nowSec();
  const user = await db
    .prepare('SELECT free_build_state AS s FROM users WHERE id = ?')
    .bind(userId)
    .first<{ s: FreeBuildState }>();
  const freeBuild: FreeBuildState = user?.s ?? 'AVAILABLE';

  const sub = await db
    .prepare(
      `SELECT plan_id AS planId, expires_at AS exp FROM subscriptions
       WHERE user_id = ? AND status = 'active' AND (expires_at IS NULL OR expires_at > ?)
       ORDER BY expires_at IS NULL DESC, expires_at DESC LIMIT 1`,
    )
    .bind(userId, now)
    .first<{ planId: string; exp: number | null }>();

  let planId = 'FREE';
  let expiresAt: number | null = null;
  let subscribed = false;
  if (sub) {
    planId = sub.planId;
    expiresAt = sub.exp;
    subscribed = true;
  } else {
    const grant = await db
      .prepare(
        `SELECT type AS t, expires_at AS exp FROM entitlements
         WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?) LIMIT 1`,
      )
      .bind(userId, now)
      .first<{ t: string; exp: number | null }>();
    if (grant) {
      planId = grant.t;
      expiresAt = grant.exp;
      subscribed = true;
    }
  }
  return { subscribed, planId, expiresAt, freeBuild, buildsAllowed: subscribed || freeBuild === 'AVAILABLE' };
}

// ATOMIC claim: exactly one concurrent caller wins (single conditional UPDATE).
export async function claimFreeBuild(db: D1Database, userId: string, buildId: string): Promise<boolean> {
  const r = await db
    .prepare(
      `UPDATE users SET free_build_state = 'RESERVED', free_build_id = ?, updated_at = ?
       WHERE id = ? AND free_build_state = 'AVAILABLE'`,
    )
    .bind(buildId, nowSec(), userId)
    .run();
  return (r.meta.changes ?? 0) === 1;
}

export async function consumeFreeBuild(db: D1Database, userId: string, buildId: string): Promise<boolean> {
  const r = await db
    .prepare(
      `UPDATE users SET free_build_state = 'CONSUMED', updated_at = ?
       WHERE id = ? AND free_build_state = 'RESERVED' AND free_build_id = ?`,
    )
    .bind(nowSec(), userId, buildId)
    .run();
  return (r.meta.changes ?? 0) === 1;
}

export async function releaseFreeBuild(db: D1Database, userId: string, buildId: string): Promise<boolean> {
  const r = await db
    .prepare(
      `UPDATE users SET free_build_state = 'AVAILABLE', free_build_id = NULL, updated_at = ?
       WHERE id = ? AND free_build_state = 'RESERVED' AND free_build_id = ?`,
    )
    .bind(nowSec(), userId, buildId)
    .run();
  return (r.meta.changes ?? 0) === 1;
}
