package com.apiintel.schedule;

import com.apiintel.reports.ReportService;
import com.apiintel.security.PlatformUserService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Owner-scoped schedule CRUD and next-run computation (DST-aware). */
@Service
public class ScheduleService {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;

  private final JdbcTemplate db;
  private final PlatformUserService users;
  private final ReportService reports;

  public ScheduleService(JdbcTemplate db, PlatformUserService users, ReportService reports) {
    this.db = db;
    this.users = users;
    this.reports = reports;
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }

  public List<ObjectNode> list(String username) {
    return db.query("SELECT * FROM schedules WHERE owner_id=? ORDER BY created_at",
        this::row, ownerId(username));
  }

  public ObjectNode get(String id, String username) {
    ObjectNode s = rowById(id);
    if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found");
    if (!ownerId(username).equals(s.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Schedule belongs to another user");
    }
    return s;
  }

  public ObjectNode save(ObjectNode input, String username) {
    String ownerId = ownerId(username);
    String id = input.path("id").asText("");
    ObjectNode existing = id.isBlank() ? null : get(id, username);
    String reportId = input.path("reportId").asText(existing == null ? "" : existing.path("reportId").asText());
    reports.definition(reportId, username); // owner check + existence
    String kind = input.path("kind").asText(existing == null ? "DAILY" : existing.path("kind").asText());
    String expr = input.path("expr").asText(existing == null ? "09:00" : existing.path("expr").asText());
    String zone = input.path("zone").asText(existing == null ? "UTC" : existing.path("zone").asText());
    Instant next = nextAfter(kind, expr, ZoneId.of(zone), Instant.now());
    if (next == null) throw new IllegalArgumentException("Schedule produces no future run");
    String now = Instant.now().toString();
    if (existing == null) {
      id = UUID.randomUUID().toString();
      db.update("INSERT INTO schedules(id,owner_id,report_id,kind,expr,zone,status,missed_policy,"
          + "max_catchup,retry_limit,backoff_base_s,deadline_s,next_run_at,last_error,created_at,updated_at)"
          + " VALUES(?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?,'',?,?)",
          id, ownerId, reportId, kind, expr, zone,
          input.path("missedPolicy").asText("RUN_ONCE"),
          input.path("maxCatchup").asInt(5), input.path("retryLimit").asInt(3),
          input.path("backoffBaseS").asInt(30), input.path("deadlineS").asInt(600),
          next.toString(), now, now);
    } else {
      db.update("UPDATE schedules SET report_id=?,kind=?,expr=?,zone=?,missed_policy=?,max_catchup=?,"
          + "retry_limit=?,backoff_base_s=?,deadline_s=?,next_run_at=?,updated_at=? WHERE id=?",
          reportId, kind, expr, zone, input.path("missedPolicy").asText(existing.path("missedPolicy").asText("RUN_ONCE")),
          input.path("maxCatchup").asInt(existing.path("maxCatchup").asInt(5)),
          input.path("retryLimit").asInt(existing.path("retryLimit").asInt(3)),
          input.path("backoffBaseS").asInt(existing.path("backoffBaseS").asInt(30)),
          input.path("deadlineS").asInt(existing.path("deadlineS").asInt(600)),
          next.toString(), Instant.now().toString(), id);
    }
    return get(id, username);
  }

  public void delete(String id, String username) {
    get(id, username);
    db.update("DELETE FROM schedules WHERE id=?", id);
    db.update("UPDATE schedule_runs SET status='CANCELLED' WHERE schedule_id=? AND status IN ('QUEUED','LEASED')", id);
  }

  public ObjectNode pause(String id, String username) {
    get(id, username);
    db.update("UPDATE schedules SET status='PAUSED',updated_at=? WHERE id=?", Instant.now().toString(), id);
    return get(id, username);
  }

  public ObjectNode resume(String id, String username) {
    get(id, username);
    ObjectNode s = get(id, username);
    Instant next = nextAfter(s.path("kind").asText(), s.path("expr").asText(),
        ZoneId.of(s.path("zone").asText()), Instant.now());
    db.update("UPDATE schedules SET status='ACTIVE',next_run_at=?,updated_at=? WHERE id=?",
        next == null ? null : next.toString(), Instant.now().toString(), id);
    return get(id, username);
  }

  /** Manual run-now: unique execution key so it never collides with scheduled runs. */
  public ObjectNode runNow(String id, String username) {
    ObjectNode s = get(id, username);
    String key = id + "|manual|" + UUID.randomUUID();
    String runId = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    db.update("INSERT INTO schedule_runs(id,schedule_id,owner_id,execution_key,planned_at,status)"
        + " VALUES(?,?,?,?,?,'QUEUED')",
        runId, id, s.path("ownerId").asText(), key, now);
    return runById(runId);
  }

  public ObjectNode cancelRun(String runId, String username) {
    ObjectNode run = runById(runId);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
    if (!ownerId(username).equals(run.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Run belongs to another user");
    }
    db.update("UPDATE schedule_runs SET status='CANCELLED',finished_at=? WHERE id=? AND status IN ('QUEUED','LEASED','FAILED')",
        Instant.now().toString(), runId);
    return runById(runId);
  }

  public List<ObjectNode> history(String scheduleId, String username) {
    get(scheduleId, username);
    return db.query("SELECT * FROM schedule_runs WHERE schedule_id=? ORDER BY planned_at DESC LIMIT 100",
        this::runRow, scheduleId);
  }

  public ObjectNode runById(String runId) {
    return db.query("SELECT * FROM schedule_runs WHERE id=?", this::runRow, runId)
        .stream().findFirst().orElse(null);
  }

  public ObjectNode rowById(String id) {
    return db.query("SELECT * FROM schedules WHERE id=?", this::row, id)
        .stream().findFirst().orElse(null);
  }

  // ---------- run computation ----------

  /**
   * Next run instant after {@code from}. CRON uses Spring's CronExpression
   * evaluated in the schedule zone; DAILY/WEEKLY compute on the local calendar
   * so wall-clock times survive DST; INTERVAL is pure instant arithmetic.
   */
  public Instant nextAfter(String kind, String expr, ZoneId zone, Instant from) {
    try {
      return switch (kind) {
        case "INTERVAL" -> {
          long seconds = Long.parseLong(expr.trim());
          if (seconds <= 0) yield null;
          yield from.plusSeconds(seconds);
        }
        case "CRON" -> {
          CronExpression cron = CronExpression.parse(expr);
          ZonedDateTime next = cron.next(from.atZone(zone));
          yield next == null ? null : next.toInstant();
        }
        case "DAILY" -> {
          LocalTime time = LocalTime.parse(expr.trim());
          ZonedDateTime candidate = ZonedDateTime.of(LocalDateTime.of(
              from.atZone(zone).toLocalDate(), time), zone);
          if (!candidate.toInstant().isAfter(from)) candidate = candidate.plusDays(1);
          yield candidate.toInstant();
        }
        case "WEEKLY" -> {
          String[] parts = expr.trim().split("\\s+");
          java.time.DayOfWeek dow = java.time.DayOfWeek.valueOf(parts[0].toUpperCase());
          LocalTime time = LocalTime.parse(parts[1]);
          ZonedDateTime z = from.atZone(zone);
          for (int d = 0; d < 8; d++) {
            var date = z.toLocalDate().plusDays(d);
            if (date.getDayOfWeek() != dow) continue;
            ZonedDateTime candidate = ZonedDateTime.of(LocalDateTime.of(date, time), zone);
            if (candidate.toInstant().isAfter(from)) yield candidate.toInstant();
          }
          yield null;
        }
        default -> throw new IllegalArgumentException("Unknown schedule kind " + kind);
      };
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid schedule expression: " + e.getMessage());
    }
  }

  /** Preview N upcoming instants (DST-aware) for a kind+expr+zone. */
  public ArrayNode preview(String kind, String expr, String zone, int count) {
    ZoneId z = ZoneId.of(zone);
    ArrayNode out = J.arrayNode();
    Instant cursor = Instant.now();
    for (int i = 0; i < Math.min(count, 20); i++) {
      Instant next = nextAfter(kind, expr, z, cursor);
      if (next == null) break;
      out.add(next.toString());
      cursor = next.plusSeconds(1);
    }
    return out;
  }

  private ObjectNode row(ResultSet rs, int n) throws SQLException {
    ObjectNode o = J.objectNode();
    for (String c : List.of("id", "owner_id", "report_id", "kind", "expr", "zone", "status",
        "missed_policy", "next_run_at", "last_success_at", "last_error", "created_at", "updated_at")) {
      String v = rs.getString(c);
      if (v != null) o.put(camel(c), v);
    }
    o.put("maxCatchup", rs.getInt("max_catchup"));
    o.put("retryLimit", rs.getInt("retry_limit"));
    o.put("backoffBaseS", rs.getInt("backoff_base_s"));
    o.put("deadlineS", rs.getInt("deadline_s"));
    return o;
  }

  private ObjectNode runRow(ResultSet rs, int n) throws SQLException {
    ObjectNode o = J.objectNode();
    for (String c : List.of("id", "schedule_id", "owner_id", "execution_key", "planned_at", "status",
        "lease_owner", "lease_expires_at", "started_at", "finished_at", "error", "snapshot_id")) {
      String v = rs.getString(c);
      if (v != null) o.put(camel(c), v);
    }
    o.put("attempt", rs.getInt("attempt"));
    return o;
  }

  private String camel(String col) {
    StringBuilder out = new StringBuilder();
    for (String part : col.split("_")) {
      if (out.length() == 0) out.append(part);
      else out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return out.toString();
  }
}
