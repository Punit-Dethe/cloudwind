CREATE TABLE IF NOT EXISTS objects (
  id          TEXT PRIMARY KEY,           -- random 32-hex id chosen by the phone
  size        INTEGER NOT NULL,           -- ciphertext bytes
  uploaded_at INTEGER NOT NULL,           -- unix seconds
  synced_at   INTEGER,                    -- set when the PC confirms the hash
  purged      INTEGER NOT NULL DEFAULT 0, -- 1 once the cloud copy is deleted
  downloads   INTEGER NOT NULL DEFAULT 0  -- full downloads so far (capped)
);
CREATE INDEX IF NOT EXISTS objects_pending ON objects (synced_at, uploaded_at);

-- Multipart uploads in flight. `reserved` counts against the inbox cap until completed or aborted.
CREATE TABLE IF NOT EXISTS uploads (
  upload_id  TEXT PRIMARY KEY,
  id         TEXT NOT NULL,
  reserved   INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
