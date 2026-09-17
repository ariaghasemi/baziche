-- BAZICHE data DB (baziche-data) — Migration 001
-- Projects, revisions, assets, builds, signing, shards, agents, flags.

CREATE TABLE projects (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  game_type TEXT NOT NULL,
  format_version INTEGER NOT NULL DEFAULT 1,
  shard_id TEXT NOT NULL,
  rev INTEGER NOT NULL DEFAULT 1,
  status TEXT NOT NULL DEFAULT 'active',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_projects_user ON projects(user_id, updated_at);

CREATE TABLE project_revisions (
  id TEXT PRIMARY KEY,
  project_id TEXT NOT NULL,
  rev INTEGER NOT NULL,
  r2_key TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (project_id, rev)
);
CREATE INDEX idx_rev_project ON project_revisions(project_id, rev);

CREATE TABLE assets (
  id TEXT PRIMARY KEY,
  project_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  hash TEXT NOT NULL,
  r2_key TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_assets_project ON assets(project_id);

CREATE TABLE builds (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  project_id TEXT NOT NULL,
  rev INTEGER NOT NULL,
  provider TEXT NOT NULL DEFAULT 'github-actions',
  status TEXT NOT NULL DEFAULT 'QUEUED',
  target TEXT NOT NULL DEFAULT 'both',
  run_ref TEXT,
  log_r2_key TEXT,
  apk_r2_key TEXT,
  aab_r2_key TEXT,
  error_code TEXT,
  queued_at INTEGER NOT NULL,
  started_at INTEGER,
  finished_at INTEGER
);
CREATE INDEX idx_builds_status ON builds(status, queued_at);
CREATE INDEX idx_builds_user ON builds(user_id);

CREATE TABLE signing_keys (
  project_id TEXT PRIMARY KEY,
  keystore_r2_key TEXT NOT NULL,
  alias TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE storage_shards (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL DEFAULT 'r2',
  bucket TEXT NOT NULL,
  capacity_bytes INTEGER NOT NULL DEFAULT 10737418240,
  used_bytes INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'active',
  region TEXT NOT NULL DEFAULT 'auto',
  priority INTEGER NOT NULL DEFAULT 5,
  health TEXT NOT NULL DEFAULT 'healthy',
  error_rate REAL NOT NULL DEFAULT 0,
  latency_ms INTEGER NOT NULL DEFAULT 0,
  last_checked INTEGER NOT NULL DEFAULT 0
);

INSERT INTO storage_shards
  (id, kind, bucket, status, region, priority, health)
VALUES
  ('shard_projects_1', 'project', 'baziche-projects', 'active', 'auto', 5, 'healthy'),
  ('shard_assets_1', 'asset', 'baziche-assets', 'active', 'auto', 5, 'healthy'),
  ('shard_builds_1', 'build', 'baziche-builds', 'active', 'auto', 5, 'healthy');

CREATE TABLE agents (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'offline',
  capacity INTEGER NOT NULL DEFAULT 1,
  active_jobs INTEGER NOT NULL DEFAULT 0,
  last_heartbeat INTEGER,
  version TEXT,
  region TEXT,
  error_rate REAL NOT NULL DEFAULT 0
);

INSERT INTO agents (id, provider, status, capacity, version, region)
VALUES ('github-actions-1', 'github-actions', 'planned', 2, 'game-build/1', 'github-hosted');

CREATE TABLE feature_flags (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
