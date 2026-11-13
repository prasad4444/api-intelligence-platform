CREATE TABLE platform_users (
  id VARCHAR(64) PRIMARY KEY NOT NULL,
  username VARCHAR(80) NOT NULL UNIQUE,
  password_hash VARCHAR(200) NOT NULL,
  display_name VARCHAR(200) NOT NULL DEFAULT '',
  roles VARCHAR(200) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE scoped_secrets (
  owner_id VARCHAR(64) NOT NULL,
  connection_id VARCHAR(64) NOT NULL,
  name VARCHAR(80) NOT NULL,
  ciphertext CLOB NOT NULL,
  PRIMARY KEY (owner_id, connection_id, name)
);
CREATE INDEX scoped_secrets_owner ON scoped_secrets(owner_id, connection_id);
