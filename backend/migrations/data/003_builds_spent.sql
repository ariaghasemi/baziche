-- BAZICHE data DB (baziche-data) — Migration 003
-- What paid for this build: 'free' (free-build claim), 'oneshot' (build_single
-- entitlement), or NULL (active subscription). Drives refunds on FAILED.
ALTER TABLE builds ADD COLUMN spent TEXT;
