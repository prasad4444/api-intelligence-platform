CREATE TABLE chat_conversations (
  id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL,
  connection_id TEXT,
  provider TEXT,
  title TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  provider_state TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX chat_conversations_owner ON chat_conversations(owner_id, updated_at);

CREATE TABLE chat_messages (
  id TEXT PRIMARY KEY NOT NULL,
  conversation_id TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT NOT NULL DEFAULT '',
  provider TEXT,
  connection_id TEXT,
  account TEXT,
  status TEXT NOT NULL DEFAULT 'complete',
  meta TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL
);
CREATE INDEX chat_messages_owner ON chat_messages(owner_id);
CREATE INDEX chat_messages_conversation ON chat_messages(conversation_id, seq);

CREATE TABLE activity_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id TEXT NOT NULL,
  connection_id TEXT,
  studio_session_id TEXT,
  type TEXT NOT NULL,
  message TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);
CREATE INDEX activity_events_owner ON activity_events(owner_id, id);
CREATE INDEX activity_events_session ON activity_events(studio_session_id);
