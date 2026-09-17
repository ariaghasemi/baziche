-- BAZICHE data DB (baziche-data) — Migration 004
-- GitHub Releases binary storage references (R2 -> GitHub MVP migration).
-- ADDITIVE ONLY: every `r2_*` column keeps working as the LOGICAL object key on
-- both backends, so rollback is a config flip (BINARY_STORAGE=r2), not a restore.
-- `storage` remembers the backend per row; NULL = legacy row, follows env default.

ALTER TABLE project_revisions ADD COLUMN storage TEXT;
ALTER TABLE project_revisions ADD COLUMN release_tag TEXT;
ALTER TABLE project_revisions ADD COLUMN release_id INTEGER;
ALTER TABLE project_revisions ADD COLUMN asset_id INTEGER;
ALTER TABLE project_revisions ADD COLUMN asset_name TEXT;
ALTER TABLE project_revisions ADD COLUMN sha256 TEXT;

ALTER TABLE assets ADD COLUMN storage TEXT;
ALTER TABLE assets ADD COLUMN release_tag TEXT;
ALTER TABLE assets ADD COLUMN release_id INTEGER;
ALTER TABLE assets ADD COLUMN asset_id INTEGER;
ALTER TABLE assets ADD COLUMN asset_name TEXT;
ALTER TABLE assets ADD COLUMN sha256 TEXT;
ALTER TABLE assets ADD COLUMN content_type TEXT;
CREATE INDEX idx_assets_key ON assets(r2_key);

ALTER TABLE builds ADD COLUMN bundle_storage TEXT;
ALTER TABLE builds ADD COLUMN bundle_key TEXT;
ALTER TABLE builds ADD COLUMN bundle_release_tag TEXT;
ALTER TABLE builds ADD COLUMN bundle_asset_id INTEGER;
ALTER TABLE builds ADD COLUMN bundle_asset_name TEXT;
ALTER TABLE builds ADD COLUMN bundle_sha256 TEXT;
ALTER TABLE builds ADD COLUMN release_tag TEXT;
ALTER TABLE builds ADD COLUMN apk_asset_name TEXT;
ALTER TABLE builds ADD COLUMN apk_asset_id INTEGER;
ALTER TABLE builds ADD COLUMN aab_asset_name TEXT;
ALTER TABLE builds ADD COLUMN aab_asset_id INTEGER;
ALTER TABLE builds ADD COLUMN log_asset_name TEXT;
ALTER TABLE builds ADD COLUMN log_asset_id INTEGER;

-- Per-shard backend override for the BinaryStorage factory; NULL = BINARY_STORAGE env decides.
ALTER TABLE storage_shards ADD COLUMN backend TEXT;
