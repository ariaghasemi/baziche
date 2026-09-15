-- BAZICHE auth DB (baziche-auth) — Migration 001
-- Identity, sessions, billing, admin, audit. No game bytes here.

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
