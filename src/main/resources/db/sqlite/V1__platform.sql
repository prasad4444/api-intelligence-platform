CREATE TABLE records (
  id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL,
  payload TEXT NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX records_kind ON records(kind, updated_at DESC);
CREATE TABLE revisions (
  id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL,
  changed_at INTEGER NOT NULL, PRIMARY KEY(id, revision)
);
CREATE TABLE audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT, action TEXT, subject TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE secrets (name TEXT PRIMARY KEY NOT NULL, ciphertext TEXT NOT NULL);
