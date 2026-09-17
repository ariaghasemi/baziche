import { describe, it, expect } from 'vitest';
import { makeCtx, req, registerUser, createProject } from './setup';
import { handleScheduled } from '../src/index';

async function gunzip(data: ArrayBuffer): Promise<string> {
  const ds = new DecompressionStream('gzip');
  const stream = new Blob([data]).stream().pipeThrough(ds);
  return await new Response(stream).text();
}

describe('cron backup + /metrics', () => {
  it('backs D1 up to R2 (gzipped, restorable) and updates latest.json', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken, 'BackupMe');
    await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });

    const s = await handleScheduled(ctx.env);
    expect(s.key).toMatch(/^backups\/\d{4}-\d{2}-\d{2}\/d1-\d+\.json\.gz$/);
    expect(s.rows).toBeGreaterThan(5);

    const obj = await ctx.r2builds.get(s.key);
    expect(obj).not.toBeNull();
    const doc = JSON.parse(await gunzip(await obj!.arrayBuffer())) as {
      v: number;
      tables: Record<string, Record<string, unknown>[]>;
    };
    expect(doc.v).toBe(1);
    expect(doc.tables['auth.users'].some((r) => r.id === u.user.id)).toBe(true);
    expect(doc.tables['data.projects'].some((r) => r.id === pid)).toBe(true);
    expect(doc.tables['data.builds'].length).toBe(1);

    const latest = await ctx.r2builds.get('backups/latest.json');
    const lj = JSON.parse(await latest!.text()) as { key: string };
    expect(lj.key).toBe(s.key);
  });

  it('/metrics reports aggregate counts (no PII)', async () => {
    const ctx = await makeCtx();
    const u = await registerUser(ctx);
    const pid = await createProject(ctx, u.accessToken);
    await req(ctx, '/api/v1/builds', { method: 'POST', token: u.accessToken, body: { projectId: pid } });
    const res = await req(ctx, '/api/v1/meta/metrics');
    expect(res.status).toBe(200);
    const j = (await res.json()) as { metrics: { users: number; activeProjects: number; buildsByStatus: Record<string, number> } };
    expect(j.metrics.users).toBe(1);
    expect(j.metrics.activeProjects).toBe(1);
    expect(j.metrics.buildsByStatus.QUEUED).toBe(1);
    const text = JSON.stringify(j);
    expect(text).not.toContain(u.user.id);
    expect(text).not.toContain('09123456789');
  });
});
