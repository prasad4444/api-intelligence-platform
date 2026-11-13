CREATE TABLE platform_users (
  id TEXT PRIMARY KEY NOT NULL,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT '',
  roles TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX platform_users_username ON platform_users(username);

CREATE TABLE scoped_secrets (
  owner_id TEXT NOT NULL,
  connection_id TEXT NOT NULL,
  name TEXT NOT NULL,
  ciphertext TEXT NOT NULL,
  PRIMARY KEY (owner_id, connection_id, name)
);
CREATE INDEX scoped_secrets_owner ON scoped_secrets(owner_id, connection_id);
