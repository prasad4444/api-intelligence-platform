CREATE TABLE report_snapshots (
  id VARCHAR(64) PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  report_version INTEGER NOT NULL,
  execution_key VARCHAR(255) NOT NULL UNIQUE,
  planned_at VARCHAR(64) NOT NULL,
  evaluated_at VARCHAR(64) NOT NULL,
  resolved_windows CLOB NOT NULL DEFAULT '{}',
  metrics CLOB NOT NULL DEFAULT '[]',
  series CLOB NOT NULL DEFAULT '{}',
  freshness CLOB NOT NULL DEFAULT '{}',
  completeness VARCHAR(255) NOT NULL DEFAULT '',
  errors CLOB NOT NULL DEFAULT '[]',
  ai_summary CLOB NOT NULL DEFAULT '',
  created_at VARCHAR(64) NOT NULL
);
CREATE INDEX report_snapshots_owner ON report_snapshots(owner_id, created_at);
CREATE INDEX report_snapshots_report ON report_snapshots(report_id, created_at);

CREATE TABLE schedules (
  id VARCHAR(64) PRIMARY KEY,
  owner_id VARCHAR(64) NOT NULL,
  report_id VARCHAR(64) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  expr VARCHAR(255) NOT NULL DEFAULT '',
  zone VARCHAR(64) NOT NULL DEFAULT 'UTC',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  missed_policy VARCHAR(32) NOT NULL DEFAULT 'RUN_ONCE',
  max_catchup INTEGER NOT NULL DEFAULT 5,
  retry_limit INTEGER NOT NULL DEFAULT 3,
  backoff_base_s INTEGER NOT NULL DEFAULT 30,
  deadline_s INTEGER NOT NULL DEFAULT 600,
  next_run_at VARCHAR(64),
  last_success_at VARCHAR(64),
  last_error VARCHAR(2000) NOT NULL DEFAULT '',
  created_at VARCHAR(64) NOT NULL,
  updated_at VARCHAR(64) NOT NULL
);
CREATE INDEX schedules_owner ON schedules(owner_id);
CREATE INDEX schedules_next ON schedules(status, next_run_at);

CREATE TABLE schedule_runs (
  id VARCHAR(64) PRIMARY KEY,
  schedule_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  execution_key VARCHAR(255) NOT NULL UNIQUE,
  planned_at VARCHAR(64) NOT NULL,
  attempt INTEGER NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
  next_attempt_at VARCHAR(64),
  lease_owner VARCHAR(64),
  lease_expires_at VARCHAR(64),
  started_at VARCHAR(64),
  finished_at VARCHAR(64),
  error VARCHAR(2000) NOT NULL DEFAULT '',
  snapshot_id VARCHAR(64)
);
CREATE INDEX schedule_runs_schedule ON schedule_runs(schedule_id, planned_at);
CREATE INDEX schedule_runs_owner ON schedule_runs(owner_id);
CREATE INDEX schedule_runs_status ON schedule_runs(status, lease_expires_at);

CREATE TABLE alert_incidents (
  id VARCHAR(64) PRIMARY KEY,
  rule_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(64) NOT NULL,
  entity_key VARCHAR(255) NOT NULL DEFAULT '',
  episode VARCHAR(128) NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  kind VARCHAR(48) NOT NULL DEFAULT 'SERVICE_ERROR',
  opened_at VARCHAR(64) NOT NULL,
  last_eval_at VARCHAR(64) NOT NULL,
  consecutive_breaches INTEGER NOT NULL DEFAULT 0,
  consecutive_ok INTEGER NOT NULL DEFAULT 0,
  last_snapshot_id VARCHAR(64),
  resolved_at VARCHAR(64)
);
CREATE UNIQUE INDEX alert_incidents_dedup ON alert_incidents(rule_id, entity_key, episode);
CREATE INDEX alert_incidents_owner ON alert_incidents(owner_id, state);

CREATE TABLE notification_outbox (
  id VARCHAR(64) PRIMARY KEY,
  owner_id VARCHAR(64) NOT NULL,
  dedup_key VARCHAR(255) NOT NULL UNIQUE,
  destination_id VARCHAR(64),
  payload CLOB NOT NULL DEFAULT '{}',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at VARCHAR(64),
  last_error VARCHAR(2000) NOT NULL DEFAULT '',
  idempotency_key VARCHAR(128) NOT NULL,
  created_at VARCHAR(64) NOT NULL,
  sent_at VARCHAR(64)
);
CREATE INDEX notification_outbox_status ON notification_outbox(status, next_attempt_at);
CREATE INDEX notification_outbox_owner ON notification_outbox(owner_id);

CREATE TABLE in_app_notifications (
  id VARCHAR(64) PRIMARY KEY,
  owner_id VARCHAR(64) NOT NULL,
  type VARCHAR(64) NOT NULL DEFAULT '',
  title VARCHAR(255) NOT NULL DEFAULT '',
  body VARCHAR(4000) NOT NULL DEFAULT '',
  is_read INTEGER NOT NULL DEFAULT 0,
  created_at VARCHAR(64) NOT NULL
);
CREATE INDEX in_app_notifications_owner ON in_app_notifications(owner_id, is_read, created_at);

CREATE TABLE knowledge_items (
  id VARCHAR(64) PRIMARY KEY,
  owner_id VARCHAR(64) NOT NULL,
  kind VARCHAR(48) NOT NULL,
  title VARCHAR(255) NOT NULL DEFAULT '',
  body CLOB NOT NULL DEFAULT '',
  source_refs CLOB NOT NULL DEFAULT '[]',
  dependency_hash VARCHAR(128) NOT NULL DEFAULT '',
  valid INTEGER NOT NULL DEFAULT 1,
  created_at VARCHAR(64) NOT NULL,
  updated_at VARCHAR(64) NOT NULL
);
CREATE INDEX knowledge_items_owner ON knowledge_items(owner_id, kind);
CREATE INDEX knowledge_items_valid ON knowledge_items(valid);
