CREATE TABLE report_snapshots (
  id TEXT PRIMARY KEY NOT NULL,
  report_id TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  report_version INTEGER NOT NULL,
  execution_key TEXT NOT NULL UNIQUE,
  planned_at TEXT NOT NULL,
  evaluated_at TEXT NOT NULL,
  resolved_windows TEXT NOT NULL DEFAULT '{}',
  metrics TEXT NOT NULL DEFAULT '[]',
  series TEXT NOT NULL DEFAULT '{}',
  freshness TEXT NOT NULL DEFAULT '{}',
  completeness TEXT NOT NULL DEFAULT '',
  errors TEXT NOT NULL DEFAULT '[]',
  ai_summary TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);
CREATE INDEX report_snapshots_owner ON report_snapshots(owner_id, created_at);
CREATE INDEX report_snapshots_report ON report_snapshots(report_id, created_at);

CREATE TABLE schedules (
  id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL,
  report_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  expr TEXT NOT NULL DEFAULT '',
  zone TEXT NOT NULL DEFAULT 'UTC',
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  missed_policy TEXT NOT NULL DEFAULT 'RUN_ONCE',
  max_catchup INTEGER NOT NULL DEFAULT 5,
  retry_limit INTEGER NOT NULL DEFAULT 3,
  backoff_base_s INTEGER NOT NULL DEFAULT 30,
  deadline_s INTEGER NOT NULL DEFAULT 600,
  next_run_at TEXT,
  last_success_at TEXT,
  last_error TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX schedules_owner ON schedules(owner_id);
CREATE INDEX schedules_next ON schedules(status, next_run_at);

CREATE TABLE schedule_runs (
  id TEXT PRIMARY KEY NOT NULL,
  schedule_id TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  execution_key TEXT NOT NULL UNIQUE,
  planned_at TEXT NOT NULL,
  attempt INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'QUEUED',
  next_attempt_at TEXT,
  lease_owner TEXT,
  lease_expires_at TEXT,
  started_at TEXT,
  finished_at TEXT,
  error TEXT NOT NULL DEFAULT '',
  snapshot_id TEXT
);
CREATE INDEX schedule_runs_schedule ON schedule_runs(schedule_id, planned_at);
CREATE INDEX schedule_runs_owner ON schedule_runs(owner_id);
CREATE INDEX schedule_runs_status ON schedule_runs(status, lease_expires_at);

CREATE TABLE alert_incidents (
  id TEXT PRIMARY KEY NOT NULL,
  rule_id TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  entity_key TEXT NOT NULL DEFAULT '',
  episode TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'PENDING',
  kind TEXT NOT NULL DEFAULT 'SERVICE_ERROR',
  opened_at TEXT NOT NULL,
  last_eval_at TEXT NOT NULL,
  consecutive_breaches INTEGER NOT NULL DEFAULT 0,
  consecutive_ok INTEGER NOT NULL DEFAULT 0,
  last_snapshot_id TEXT,
  resolved_at TEXT
);
CREATE UNIQUE INDEX alert_incidents_dedup ON alert_incidents(rule_id, entity_key, episode);
CREATE INDEX alert_incidents_owner ON alert_incidents(owner_id, state);

CREATE TABLE notification_outbox (
  id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL,
  dedup_key TEXT NOT NULL UNIQUE,
  destination_id TEXT,
  payload TEXT NOT NULL DEFAULT '{}',
  status TEXT NOT NULL DEFAULT 'PENDING',
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TEXT,
  last_error TEXT NOT NULL DEFAULT '',
  idempotency_key TEXT NOT NULL,
  created_at TEXT NOT NULL,
  sent_at TEXT
);
CREATE INDEX notification_outbox_status ON notification_outbox(status, next_attempt_at);
CREATE INDEX notification_outbox_owner ON notification_outbox(owner_id);

CREATE TABLE in_app_notifications (
  id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL,
  type TEXT NOT NULL DEFAULT '',
  title TEXT NOT NULL DEFAULT '',
  body TEXT NOT NULL DEFAULT '',
  is_read INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE INDEX in_app_notifications_owner ON in_app_notifications(owner_id, is_read, created_at);

CREATE TABLE knowledge_items (
  id TEXT PRIMARY KEY NOT NULL,
  owner_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  body TEXT NOT NULL DEFAULT '',
  source_refs TEXT NOT NULL DEFAULT '[]',
  dependency_hash TEXT NOT NULL DEFAULT '',
  valid INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX knowledge_items_owner ON knowledge_items(owner_id, kind);
CREATE INDEX knowledge_items_valid ON knowledge_items(valid);

CREATE VIRTUAL TABLE knowledge_fts USING fts5(
  title, body, content='knowledge_items', content_rowid='rowid'
);
CREATE TRIGGER knowledge_fts_ai AFTER INSERT ON knowledge_items BEGIN
  INSERT INTO knowledge_fts(rowid, title, body) VALUES (new.rowid, new.title, new.body);
END;
CREATE TRIGGER knowledge_fts_ad AFTER DELETE ON knowledge_items BEGIN
  INSERT INTO knowledge_fts(knowledge_fts, rowid, title, body) VALUES('delete', old.rowid, old.title, old.body);
END;
CREATE TRIGGER knowledge_fts_au AFTER UPDATE ON knowledge_items BEGIN
  INSERT INTO knowledge_fts(knowledge_fts, rowid, title, body) VALUES('delete', old.rowid, old.title, old.body);
  INSERT INTO knowledge_fts(rowid, title, body) VALUES (new.rowid, new.title, new.body);
END;
