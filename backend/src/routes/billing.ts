import { Hono } from 'hono';
import { z } from 'zod';
import type { Env } from '../index';
import { err, newId, nowSec } from '../lib/errors';
import { parse, zId } from '../lib/validate';
import { requireAuth } from '../middleware/auth';
import { verifyMyketPurchase } from '../lib/myket';
import { audit } from '../lib/audit';

export const billingRoutes = new Hono<{ Bindings: Env; Variables: { userId: string } }>();
billingRoutes.use('*', requireAuth);

// SKU -> grant mapping (SKUs are created in the Myket developer panel, same IDs).
const SKU_PLANS: Record<string, { planId: string; days: number | null }> = {
  sub_monthly: { planId: 'MONTHLY', days: 30 },
  sub_quarterly: { planId: 'QUARTERLY', days: 90 },
  sub_yearly: { planId: 'YEARLY', days: 365 },
  sub_lifetime: { planId: 'LIFETIME', days: null },
};
const ONE_SHOT_SKU = 'build_single';

// POST /api/v1/billing/verify — server-side Myket verification. NEVER trusts the APK.
billingRoutes.post('/verify', async (c) => {
  const userId = c.get('userId');
  const b = parse(
    z.object({
      packageName: z.string().min(1).max(128).optional(),
      sku: zId,
      token: z.string().min(1).max(512),
      developerPayload: z.string().max(512).optional(),
    }),
    await c.req.json(),
  );
  const pkg = b.packageName || c.env.MYKET_PACKAGE || '';
  if (!pkg) throw err('VALIDATION_ERROR', 'packageName required (body or MYKET_PACKAGE)', 400);

  const dup = await c.env.DB_AUTH.prepare('SELECT id FROM purchases WHERE token = ?').bind(b.token).first<{ id: string }>();
  if (dup) throw err('PURCHASE_REPLAY', 'This purchase token was already used', 409);

  if (!c.env.MYKET_ACCESS_TOKEN) throw err('NOT_IMPLEMENTED', 'Myket billing is not configured on this server', 501);

  const plan = SKU_PLANS[b.sku];
  const oneShot = b.sku === ONE_SHOT_SKU;
  if (!plan && !oneShot) throw err('UNKNOWN_PLAN', `Unknown SKU: ${b.sku}`, 400);

  const v = await verifyMyketPurchase(c.env.MYKET_ACCESS_TOKEN, pkg, b.sku, b.token);
  const now = nowSec();
  if (!v.ok) {
    await audit(c.env.DB_AUTH, 'purchase.invalid', userId, { sku: b.sku, state: v.purchaseState ?? -1 });
    throw err('PURCHASE_INVALID', 'Purchase was not approved by Myket', 402);
  }

  const pid = newId('pur');
  await c.env.DB_AUTH.prepare(
    `INSERT INTO purchases (id, user_id, provider, sku, token, state, payload, verified_at, created_at)
     VALUES (?, ?, 'myket', ?, ?, 'VERIFIED', ?, ?, ?)`,
  )
    .bind(pid, userId, b.sku, b.token, JSON.stringify({ pkg, purchaseTime: v.purchaseTime ?? null, consumptionState: v.consumptionState ?? null, developerPayload: b.developerPayload ?? v.developerPayload ?? null }), now, now)
    .run();

  if (plan) {
    const exp = plan.days === null ? null : now + plan.days * 86400;
    await c.env.DB_AUTH.prepare(
      `INSERT INTO subscriptions (id, user_id, plan_id, started_at, expires_at, status, provider, purchase_id, created_at)
       VALUES (?, ?, ?, ?, ?, 'active', 'myket', ?, ?)`,
    )
      .bind(newId('sub'), userId, plan.planId, now, exp, pid, now)
      .run();
    await audit(c.env.DB_AUTH, 'purchase.verified', userId, { sku: b.sku, plan: plan.planId });
    return c.json({ success: true, purchase: { id: pid, sku: b.sku, state: 'VERIFIED' }, granted: { kind: 'subscription', planId: plan.planId, expiresAt: exp } });
  }
  await c.env.DB_AUTH.prepare(`INSERT INTO entitlements (id, user_id, type, source, expires_at, created_at) VALUES (?, ?, 'build_single', 'myket', NULL, ?)`)
    .bind(newId('ent'), userId, now)
    .run();
  await audit(c.env.DB_AUTH, 'purchase.verified', userId, { sku: b.sku, grant: 'build_single' });
  return c.json({ success: true, purchase: { id: pid, sku: b.sku, state: 'VERIFIED' }, granted: { kind: 'oneshot', builds: 1 } });
});
