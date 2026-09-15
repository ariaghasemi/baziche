import { describe, expect, it } from 'vitest';
import { claimFreeBuild, consumeFreeBuild, getEntitlement, releaseFreeBuild } from '../src/lib/entitlements';
import { makeCtx, registerUser } from './setup';

describe('entitlements + free build atomicity', () => {
  it('new user: FREE plan, AVAILABLE free build, builds allowed once', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx);
    const e = await getEntitlement(ctx.env.DB, s.user.id);
    expect(e.planId).toBe('FREE');
    expect(e.freeBuild).toBe('AVAILABLE');
    expect(e.buildsAllowed).toBe(true);
  });

  it('atomic claim: exactly one winner, release reopens, consume closes', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120003333', 'freebuilder');
    const db = ctx.env.DB;

    // Simulated race: N concurrent claim attempts, exactly one wins.
    // NOTE: sql.js runs synchronously, so this validates the conditional-UPDATE
    // logic; on D1 the single-statement UPDATE ... WHERE is the atomicity guarantee.
    const results = await Promise.all([
      claimFreeBuild(db, s.user.id, 'build_A'),
      claimFreeBuild(db, s.user.id, 'build_B'),
      claimFreeBuild(db, s.user.id, 'build_C'),
    ]);
    expect(results.filter(Boolean).length).toBe(1);

    const reserved = (await db.prepare('SELECT free_build_state AS s FROM users WHERE id = ?').bind(s.user.id).first<{ s: string }>());
    expect(reserved?.s).toBe('RESERVED');

    expect(await claimFreeBuild(db, s.user.id, 'build_D')).toBe(false); // still reserved
    expect(await releaseFreeBuild(db, s.user.id, 'build_WRONG')).toBe(false); // wrong build id
    const winner = results[0] ? 'build_A' : results[1] ? 'build_B' : 'build_C';
    expect(await releaseFreeBuild(db, s.user.id, winner)).toBe(true); // failure path reopens

    expect(await claimFreeBuild(db, s.user.id, 'build_E')).toBe(true);
    expect(await consumeFreeBuild(db, s.user.id, 'build_E')).toBe(true); // success path closes

    const e = await getEntitlement(db, s.user.id);
    expect(e.freeBuild).toBe('CONSUMED');
    expect(e.buildsAllowed).toBe(false);
  });

  it('active subscription allows builds without free build', async () => {
    const ctx = await makeCtx();
    const s = await registerUser(ctx, '09120004444', 'subscriber');
    const db = ctx.env.DB;
    const now = Math.floor(Date.now() / 1000);
    ctx.db.run('INSERT INTO subscriptions (id, user_id, plan_id, started_at, expires_at, status, created_at) VALUES (?,?,?,?,?,?,?)', [
      'sub_1', s.user.id, 'MONTHLY', now, now + 30 * 86400, 'active', now,
    ]);
    ctx.db.run("UPDATE users SET free_build_state = 'CONSUMED' WHERE id = ?", [s.user.id]);
    const e = await getEntitlement(db, s.user.id);
    expect(e.subscribed).toBe(true);
    expect(e.planId).toBe('MONTHLY');
    expect(e.buildsAllowed).toBe(true);
  });
});
