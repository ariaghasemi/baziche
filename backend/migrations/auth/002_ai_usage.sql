-- BAZICHE auth DB (baziche-auth) — Migration 002
-- Hourly AI-expansion quota per user (attempts count, abuse protection).
CREATE TABLE ai_usage (
  user_id TEXT NOT NULL,
  hour INTEGER NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, hour)
);
