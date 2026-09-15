-- BAZICHE Migration 001 — core schema (Phase 1)
-- D1 = metadata only. Bytes live in R2. All PKs are app-generated TEXT ids.

-- users & auth
CREATE TABLE users (
  id TEXT PRIMARY KEY,
  phone TEXT NOT NULL UNIQUE,
  username TEXT NOT NULL,
  username_lower TEXT NOT NULL UNIQUE,
  salt TEXT NOT NULL,
  iterations INTEGER NOT NULL DEFAULT 100000,
  server_hash TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  free_build_state TEXT NOT NULL DEFAULT 'AVAILABLE',
  free_build_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  last_login INTEGER
);
CREATE INDEX idx_users_phone ON users(phone);

CREATE TABLE refresh_tokens (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at INTEGER NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0,
  device TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_rt_user ON refresh_tokens(user_id);

CREATE TABLE login_attempts (
  phone TEXT NOT NULL,
  ip TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX idx_attempts_at ON login_attempts(at);

-- plans (DB-driven pricing) + subscriptions + entitlement grants + purchases
CREATE TABLE plans (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  price_toman INTEGER,
  days INTEGER,
  active INTEGER NOT NULL DEFAULT 1
);
INSERT INTO plans (id, title, price_toman, days, active) VALUES
  ('FREE', 'Free', 0, NULL, 1),
  ('MONTHLY', 'Monthly', 300000, 30, 1),
  ('QUARTERLY', 'Quarterly', 500000, 90, 1),
  ('YEARLY', 'Yearly', 1000000, 365, 1),
  ('LIFETIME', 'Lifetime', NULL, NULL, 1);

CREATE TABLE subscriptions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  plan_id TEXT NOT NULL,
  started_at INTEGER NOT NULL,
  expires_at INTEGER,
  status TEXT NOT NULL DEFAULT 'active',
  provider TEXT,
  purchase_id TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_sub_user ON subscriptions(user_id, status);

CREATE TABLE entitlements (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  type TEXT NOT NULL,
  source TEXT NOT NULL,
  expires_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_ent_user ON entitlements(user_id);

CREATE TABLE purchases (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  provider TEXT NOT NULL,
  sku TEXT NOT NULL,
  token TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL,
  payload TEXT,
  verified_at INTEGER,
  created_at INTEGER NOT NULL
);

-- projects & revisions & assets
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
  created_at INTEGER NOT NULL
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

-- builds (consumed in Phase 5; schema ready now for stable planning)
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

-- storage shards (router registry; single healthy shard in MVP)
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
INSERT INTO storage_shards (id, kind, bucket, status, region, priority, health)
VALUES ('shard_1', 'r2', 'baziche-projects', 'active', 'auto', 5, 'healthy');

-- build agents registry (Phase 5 consumer; one logical agent seeded as planned/offline)
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

CREATE TABLE admins (
  user_id TEXT PRIMARY KEY,
  role TEXT NOT NULL DEFAULT 'admin',
  created_at INTEGER NOT NULL
);

CREATE TABLE audit_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at INTEGER NOT NULL,
  user_id TEXT,
  action TEXT NOT NULL,
  meta TEXT
);
CREATE INDEX idx_audit_at ON audit_logs(at);

CREATE TABLE idempotency_keys (
  key TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  at INTEGER NOT NULL,
  response TEXT NOT NULL
);
