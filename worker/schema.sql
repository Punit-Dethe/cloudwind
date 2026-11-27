CREATE TABLE IF NOT EXISTS objects (
  id          TEXT PRIMARY KEY,           -- random 32-hex id chosen by the phone
  size        INTEGER NOT NULL,           -- ciphertext bytes
  uploaded_at INTEGER NOT NULL,           -- unix seconds
  synced_at   INTEGER,                    -- set when the PC confirms the hash
  purged      INTEGER NOT NULL DEFAULT 0  -- 1 once the cloud copy is deleted
);
CREATE INDEX IF NOT EXISTS objects_pending ON objects (synced_at, uploaded_at);
