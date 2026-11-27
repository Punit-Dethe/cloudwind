-- For databases created before the limits work. Fresh installs use schema.sql only.
ALTER TABLE objects ADD COLUMN downloads INTEGER NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS uploads (
  upload_id  TEXT PRIMARY KEY,
  id         TEXT NOT NULL,
  reserved   INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
