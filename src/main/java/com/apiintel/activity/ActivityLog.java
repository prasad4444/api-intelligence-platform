package com.apiintel.activity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Append-only activity timeline for connection studio and chat flows.
 * Messages are always run through {@link #redact(String)} so bearer tokens,
 * cookies and query-string secrets can never land in {@code activity_events}.
 */
@Service
public class ActivityLog {

  public static final Set<String> TYPES = Set.of(
    "BROWSER_OPENED", "WAITING_FOR_SIGN_IN", "IDENTITY_VERIFIED", "RECORDING_STARTED",
    "REQUEST_CAPTURED", "CONNECTOR_INFERRED", "CONNECTOR_VALIDATED", "REFRESH_ATTEMPTED",
    "REFRESH_SUCCEEDED", "USER_ACTION_REQUIRED", "CANCELLED", "ERROR", "DISCONNECTED",
    "SCHEMA_DRIFT"
  );

  private static final Pattern SECRET_QUERY =
    Pattern.compile("(?i)([?&](?:access_token|token|api[_-]?key|key|secret|password|sig|signature|code)=)[^&\\s]{4,}");
  private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]{8,}");
  private static final Pattern COOKIE_PAIR = Pattern.compile("(?i)(cookie|set-cookie)\\s*[:=]\\s*[^;\\s]{4,}[^;]*");

  private final JdbcTemplate db;

  public ActivityLog(JdbcTemplate db) {
    this.db = db;
  }

  public void emit(String ownerId, String connectionId, String studioSessionId, String type, String message) {
    if (!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported activity type");
    db.update(
      "INSERT INTO activity_events(owner_id,connection_id,studio_session_id,type,message,created_at) VALUES(?,?,?,?,?,?)",
      ownerId, connectionId, studioSessionId, type,
      redact(message == null ? "" : message), Instant.now().toString());
  }

  public List<Map<String, Object>> timeline(String ownerId, String connectionId, String studioSessionId, int limit) {
    if (studioSessionId != null) {
      return db.queryForList(
        "SELECT * FROM activity_events WHERE owner_id=? AND studio_session_id=? ORDER BY id LIMIT ?",
        ownerId, studioSessionId, Math.min(limit, 1000));
    }
    if (connectionId != null) {
      return db.queryForList(
        "SELECT * FROM activity_events WHERE owner_id=? AND connection_id=? ORDER BY id LIMIT ?",
        ownerId, connectionId, Math.min(limit, 1000));
    }
    return db.queryForList(
      "SELECT * FROM activity_events WHERE owner_id=? ORDER BY id DESC LIMIT ?",
      ownerId, Math.min(limit, 1000));
  }

  public void deleteConnection(String ownerId, String connectionId) {
    db.update("DELETE FROM activity_events WHERE owner_id=? AND connection_id=?", ownerId, connectionId);
  }

  public void emitTo(ObjectNode event) {
    emit(
      event.path("ownerId").asText(),
      event.path("connectionId").asText(null),
      event.path("studioSessionId").asText(null),
      event.path("type").asText(),
      event.path("message").asText(""));
  }

  public static String redact(String text) {
    String out = SECRET_QUERY.matcher(text).replaceAll("$1REDACTED");
    out = BEARER.matcher(out).replaceAll("Bearer REDACTED");
    out = COOKIE_PAIR.matcher(out).replaceAll("$1=REDACTED");
    return out;
  }
}
