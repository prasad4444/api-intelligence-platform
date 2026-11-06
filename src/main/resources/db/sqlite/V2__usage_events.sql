CREATE TABLE usage_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  request_id TEXT NOT NULL, layer TEXT NOT NULL, source TEXT NOT NULL, method TEXT NOT NULL,
  host TEXT NOT NULL, status INTEGER NOT NULL, reason TEXT NOT NULL, duration_ms INTEGER NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX usage_events_request ON usage_events(request_id, id DESC);
CREATE INDEX usage_events_layer_status ON usage_events(layer, status);
