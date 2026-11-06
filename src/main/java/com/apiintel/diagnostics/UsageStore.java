package com.apiintel.diagnostics;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UsageStore {
  private final JdbcTemplate db;
  private final AtomicLong writeFailures = new AtomicLong();

  public UsageStore(JdbcTemplate db) { this.db = db; }

  public void record(String requestId, String layer, String source, String method, String host, int status, String reason, long duration) {
    try {
      db.update("""
        INSERT INTO usage_events(request_id,layer,source,method,host,status,reason,duration_ms,created_at)
        VALUES(?,?,?,?,?,?,?,?,?)
        """, requestId, layer, source, method, host, status, reason, duration, Instant.now().toString());
    } catch (DataAccessException error) {
      long failures = writeFailures.incrementAndGet();
      if (failures == 1 || failures % 100 == 0) LoggerFactory.getLogger(UsageStore.class)
        .warn("event=usage_storage_failed requestId={} errorType={} failedWrites={}", requestId, error.getClass().getSimpleName(), failures);
    }
  }

  public record Event(long id, String requestId, String layer, String source, String method,
    String host, int status, String reason, long durationMs, String createdAt) {}

  public List<Event> recent(String requestId, long beforeId, int limit) {
    String query = """
      SELECT id,request_id,layer,source,method,host,status,reason,duration_ms,created_at
      FROM usage_events WHERE id < ?
      """;
    boolean filtered = requestId != null && !requestId.isBlank();
    var args = new java.util.ArrayList<Object>();
    args.add(beforeId > 0 ? beforeId : Long.MAX_VALUE);
    if (filtered) {
      if (!requestId.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Enter the request ID shown in the console");
      query += " AND request_id = ?";
      args.add(requestId);
    }
    args.add(Math.min(Math.max(limit, 1), 200));
    return db.query(query + " ORDER BY id DESC LIMIT ?", (row, index) -> new Event(
      row.getLong("id"), row.getString("request_id"), row.getString("layer"), row.getString("source"),
      row.getString("method"), row.getString("host"), row.getInt("status"), row.getString("reason"),
      row.getLong("duration_ms"), row.getString("created_at")), args.toArray());
  }

  public Map<String, Long> totals() {
    return Map.of(
      "platformRequests", count("layer = 'platform'"),
      "outboundAttempts", count("layer = 'upstream'"),
      "platformUnauthorized", count("layer = 'platform' AND status = 401"),
      "upstreamUnauthorized", count("layer = 'upstream' AND status = 401"),
      "blockedBeforeSend", count("layer = 'preflight'"),
      "transportFailures", count("layer = 'upstream' AND status = 0")
    );
  }

  private long count(String condition) {
    Long count = db.queryForObject("SELECT COUNT(*) FROM usage_events WHERE " + condition, Long.class);
    return count == null ? 0 : count;
  }
}
