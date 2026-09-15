// StorageRouter — the ONLY place that decides where bytes live.
// MVP: single healthy shard. Scoring is real and ready for multi-shard scale-out
// (additional buckets/accounts added ONLY as compliant, paid, owned resources).
import { nowSec } from './errors';

export type ShardKind = 'project' | 'asset' | 'build';

export interface Shard {
  id: string;
  kind: string; // 'r2' (future: 's3', 'r2b' ...)
  bucket: string;
  capacityBytes: number;
  usedBytes: number;
  status: string; // 'active' | 'readonly' | 'disabled'
  region: string;
  priority: number;
  health: string; // 'healthy' | 'degraded' | 'down'
  errorRate: number; // 0..1 rolling
  latencyMs: number;
  lastChecked: number;
}

export interface IStorageRouter {
  resolveShard(db: D1Database, kind: ShardKind): Promise<Shard>;
  keyFor(shard: Shard, kind: ShardKind, ...parts: string[]): string;
}

function score(s: Shard): number {
  if (s.status !== 'active' || s.health === 'down') return -Infinity;
  const headroom = s.capacityBytes > 0 ? 1 - s.usedBytes / s.capacityBytes : 0.5;
  const healthW = s.health === 'healthy' ? 1 : 0.4;
  // weights: health 40, headroom 30, latency 15, errors 10, priority 5
  return (
    healthW * 40 +
    Math.max(0, headroom) * 30 -
    Math.min(1, s.latencyMs / 2000) * 15 -
    Math.min(1, s.errorRate) * 10 +
    Math.min(10, Math.max(0, s.priority)) * 0.5
  );
}

export function keyFor(_shard: Shard, kind: ShardKind, ...parts: string[]): string {
  const clean = parts.map((p) => p.replace(/^\/+|\/+$/g, '').replace(/\.\./g, '')).join('/');
  return `${kind}s/${clean}`;
}

export const storageRouter: IStorageRouter = {
  async resolveShard(db: D1Database, kind: ShardKind): Promise<Shard> {
    const rows = await db
      .prepare(
        `SELECT id, kind, bucket, capacity_bytes AS capacityBytes, used_bytes AS usedBytes,
                status, region, priority, health, error_rate AS errorRate,
                latency_ms AS latencyMs, last_checked AS lastChecked
         FROM storage_shards WHERE status = 'active'`,
      )
      .all<Shard>();
    const cands = (rows.results ?? []).filter((s) => s.health !== 'down');
    if (cands.length === 0) throw new Error(`no healthy storage shard for ${kind}`);
    cands.sort((a, b) => score(b) - score(a));
    return cands[0];
  },
  keyFor,
};

export async function touchShard(db: D1Database, shardId: string, ok: boolean, latencyMs: number): Promise<void> {
  try {
    await db
      .prepare(
        `UPDATE storage_shards SET last_checked = ?,
           error_rate = CASE WHEN ? THEN error_rate * 0.9 ELSE MIN(1, error_rate * 0.9 + 0.1) END,
           latency_ms = ?, health = CASE WHEN ? THEN health ELSE 'degraded' END
         WHERE id = ?`,
      )
      .bind(nowSec(), ok ? 1 : 0, latencyMs, ok ? 1 : 0, shardId)
      .run();
  } catch {
    /* best effort */
  }
}
