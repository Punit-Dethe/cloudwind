CREATE TABLE IF NOT EXISTS lan_hint (
  id         INTEGER PRIMARY KEY CHECK (id = 1),
  payload    TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
