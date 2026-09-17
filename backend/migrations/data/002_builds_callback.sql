-- BAZICHE data DB (baziche-data) — Migration 002
-- Per-build callback token: the GitHub workflow reports completion with
-- `Authorization: Bearer <callback_token>` (never the user's token).
ALTER TABLE builds ADD COLUMN callback_token TEXT;
