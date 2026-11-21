package com.apiintel.schedule;

import com.apiintel.notify.NotifyService;
import com.apiintel.reports.ReportService;
import com.apiintel.security.PlatformUserService;
import com.apiintel.store.RecordStore;
import com.apiintel.store.Rows;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Durable in-app scheduler. Runs are materialized via INSERT OR IGNORE on a
 * unique execution_key (scheduleId|plannedAt), so two engine instances on one
 * database never duplicate work. Claiming is an atomic compare-and-set UPDATE;
 * crashed engines leave expired leases that the next tick reclaims.
 */
@Service
public class SchedulerEngine {

  private final JdbcTemplate db;
  private final ScheduleService schedules;
  private final ReportService reports;
  private final PlatformUserService users;
  private final RecordStore store;
  private NotifyService notify; // optional wiring to avoid a circular dep
  private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
  private final boolean enabled;
  private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
    Thread t = new Thread(r, "scheduler-worker");
    t.setDaemon(true);
    return t;
  });
  private final ConcurrentHashMap<String, Semaphore> ownerCaps = new ConcurrentHashMap<>();
  private static final Duration LEASE = Duration.ofSeconds(60);

  public SchedulerEngine(JdbcTemplate db, ScheduleService schedules, ReportService reports,
                         PlatformUserService users, RecordStore store,
                         @Value("${platform.scheduler.enabled:true}") boolean enabled) {
    this.db = db;
    this.schedules = schedules;
    this.reports = reports;
    this.users = users;
    this.store = store;
    this.enabled = enabled;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setNotify(NotifyService notify) {
    this.notify = notify;
  }

  public String instanceId() { return instanceId; }

  @Scheduled(fixedDelayString = "5000", initialDelayString = "5000")
  public void scheduledTick() {
    if (enabled) tick();
  }

  /** One scheduler pass: materialize due runs, claim, dispatch. */
  public int tick() {
    int claimed = 0;
    try {
      materialize(Instant.now());
      for (Map<String, Object> run : claimable()) {
        String id = Rows.str(run, "id");
        String owner = Rows.str(run, "owner_id");
        if (!claim(id)) continue;
        claimed++;
        Semaphore cap = ownerCaps.computeIfAbsent(owner, k -> new Semaphore(2));
        if (!cap.tryAcquire()) {
          releaseClaim(id);
          continue;
        }
        pool.submit(() -> {
          try { execute(id); } finally { cap.release(); }
        });
      }
    } catch (RuntimeException ignored) {
    }
    return claimed;
  }

  /** Materialize planned runs for ACTIVE schedules; apply missed-run policy. */
  public void materialize(Instant now) {
    for (ObjectNode s : allActive()) {
      String nextStr = s.path("nextRunAt").asText("");
      if (nextStr.isBlank()) continue;
      Instant next = Instant.parse(nextStr);
      if (next.isAfter(now)) continue;
      String policy = s.path("missedPolicy").asText("RUN_ONCE");
      int maxCatchup = s.path("maxCatchup").asInt(5);
      List<Instant> missed = new ArrayList<>();
      Instant cursor = next;
      while (!cursor.isAfter(now) && missed.size() < 50) {
        missed.add(cursor);
        cursor = schedules.nextAfter(s.path("kind").asText(), s.path("expr").asText(),
            ZoneId.of(s.path("zone").asText("UTC")), cursor);
        if (cursor == null) break;
      }
      List<Instant> toQueue = switch (policy) {
        case "SKIP" -> missed.isEmpty() ? List.of() : List.of(missed.get(missed.size() - 1));
        case "RUN_ALL_CAPPED" -> missed.size() <= maxCatchup ? missed
            : missed.subList(missed.size() - maxCatchup, missed.size());
        default -> missed.isEmpty() ? List.of() : List.of(missed.get(missed.size() - 1));
      };
      for (Instant planned : toQueue) {
        String key = s.path("id").asText() + "|" + planned.getEpochSecond();
        db.update("INSERT INTO schedule_runs(id,schedule_id,owner_id,execution_key,planned_at,status)"
            + " SELECT ?,?,?,?,?,'QUEUED' WHERE NOT EXISTS"
            + " (SELECT 1 FROM schedule_runs WHERE execution_key=?)",
            UUID.randomUUID().toString(), s.path("id").asText(), s.path("ownerId").asText(),
            key, planned.toString(), key);
      }
      Instant following = cursor;
      if (following == null || !following.isAfter(now)) {
        following = schedules.nextAfter(s.path("kind").asText(), s.path("expr").asText(),
            ZoneId.of(s.path("zone").asText("UTC")), now);
      }
      db.update("UPDATE schedules SET next_run_at=?,updated_at=? WHERE id=?",
          following == null ? null : following.toString(), Instant.now().toString(), s.path("id").asText());
    }
  }

  private List<ObjectNode> allActive() {
    return db.query("SELECT * FROM schedules WHERE status='ACTIVE'", (rs, n) -> {
      ObjectNode o = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      for (String c : new String[]{"id", "owner_id", "report_id", "kind", "expr", "zone",
          "missed_policy", "max_catchup", "next_run_at"}) {
        String v = rs.getString(c);
        if (v != null) o.put(camel(c), v);
      }
      return o;
    });
  }

  private static String camel(String col) {
    StringBuilder out = new StringBuilder();
    for (String part : col.split("_")) {
      if (out.length() == 0) out.append(part);
      else out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return out.toString();
  }

  private List<Map<String, Object>> claimable() {
    String now = Instant.now().toString();
    return db.queryForList(
        "SELECT id, owner_id AS OWNER_ID FROM schedule_runs WHERE"
        + " (status='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))"
        + " OR (status='LEASED' AND lease_expires_at < ?)", now, now);
  }

  private boolean claim(String runId) {
    String now = Instant.now().toString();
    String expiry = Instant.now().plus(LEASE).toString();
    return db.update("UPDATE schedule_runs SET status='LEASED',lease_owner=?,lease_expires_at=?,"
        + "attempt=attempt+1,started_at=? WHERE id=? AND"
        + " ((status='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))"
        + "  OR (status='LEASED' AND lease_expires_at < ?))",
        instanceId, expiry, now, runId, now, now) == 1;
  }

  private void releaseClaim(String runId) {
    db.update("UPDATE schedule_runs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL WHERE id=?",
        runId);
  }

  /** Execute one claimed run; heartbeat the lease; retry/backoff to DEAD. */
  public void execute(String runId) {
    var runRow = schedules.runById(runId);
    if (runRow == null || !"LEASED".equals(runRow.path("status").asText())) return;
    var schedule = schedules.rowById(runRow.path("scheduleId").asText());
    if (schedule == null) { finish(runId, "CANCELLED", "schedule deleted", null); return; }
    String username = usernameFor(runRow.path("ownerId").asText());
    Thread heartbeat = heartbeat(runId);
    long deadline = schedule.path("deadlineS").asLong(600) * 1000L;
    long start = System.currentTimeMillis();
    try {
      // revalidate owner + connection state at execution time
      var snapshot = reports.run(schedule.path("reportId").asText(),
          Instant.parse(runRow.path("plannedAt").asText()), runRow.path("executionKey").asText(), username);
      if (System.currentTimeMillis() - start > deadline) {
        finish(runId, "DEAD", "DEADLINE_EXCEEDED", null);
        return;
      }
      finish(runId, "SUCCEEDED", "", snapshot.path("id").asText());
      db.update("UPDATE schedules SET last_success_at=?,last_error='',updated_at=? WHERE id=?",
          Instant.now().toString(), Instant.now().toString(), schedule.path("id").asText());
    } catch (RuntimeException e) {
      boolean expired = System.currentTimeMillis() - start > deadline;
      String code = expired ? "DEADLINE_EXCEEDED" : codeOf(e);
      if (("SPLUNK_AUTH".equals(code) || "CREDENTIALS_EXPIRED".equals(code)
          || "ACCESS_REVOKED".equals(code)) && notify != null) {
        notify.notifyOnce(runRow.path("ownerId").asText(),
            "schedule-credential|" + schedule.path("id").asText(),
            "Schedule credentials expired", "Schedule " + schedule.path("id").asText()
            + " failed: reconnect the connection.");
      }
      int attempt = runRow.path("attempt").asInt(); // claim already incremented
      int retryLimit = schedule.path("retryLimit").asInt(3);
      if (attempt <= retryLimit && !expired) {
        long backoff = schedule.path("backoffBaseS").asLong(30) * (1L << Math.min(attempt - 1, 10));
        db.update("UPDATE schedule_runs SET status='QUEUED',lease_owner=NULL,lease_expires_at=NULL,"
            + "next_attempt_at=?,error=? WHERE id=?",
            Instant.now().plusSeconds(backoff).toString(), code + ": " + abbrev(e.getMessage()), runId);
      } else {
        finish(runId, "DEAD", code + ": " + abbrev(e.getMessage()), null);
      }
      db.update("UPDATE schedules SET last_error=?,updated_at=? WHERE id=?",
          code + ": " + abbrev(e.getMessage()), Instant.now().toString(), schedule.path("id").asText());
    } finally {
      heartbeat.interrupt();
    }
  }

  private void finish(String runId, String status, String error, String snapshotId) {
    db.update("UPDATE schedule_runs SET status=?,finished_at=?,error=?,snapshot_id=?,"
        + "lease_owner=NULL,lease_expires_at=NULL WHERE id=?",
        status, Instant.now().toString(), error == null ? "" : error, snapshotId, runId);
  }

  private Thread heartbeat(String runId) {
    Thread t = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(LEASE.toMillis() / 3);
          db.update("UPDATE schedule_runs SET lease_expires_at=? WHERE id=? AND lease_owner=? AND status='LEASED'",
              Instant.now().plus(LEASE).toString(), runId, instanceId);
        } catch (InterruptedException e) {
          return;
        }
      }
    });
    t.setDaemon(true);
    t.start();
    return t;
  }

  private String codeOf(RuntimeException e) {
    if (e instanceof com.apiintel.splunk.SplunkClient.SplunkException se) {
      return switch (se.code) {
        case "SPLUNK_AUTH" -> "CREDENTIALS_EXPIRED";
        case "SPLUNK_PERMISSION" -> "ACCESS_REVOKED";
        default -> se.code;
      };
    }
    return "EXECUTION_FAILED";
  }

  private String abbrev(String s) {
    return s == null ? "" : s.substring(0, Math.min(300, s.length()));
  }

  private String usernameFor(String ownerId) {
    try {
      return String.valueOf(users.require(ownerId).get("username"));
    } catch (RuntimeException e) {
      return ownerId;
    }
  }
}
