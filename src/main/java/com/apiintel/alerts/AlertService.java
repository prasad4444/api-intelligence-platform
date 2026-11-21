package com.apiintel.alerts;

import com.apiintel.notify.NotifyService;
import com.apiintel.reports.ReportService;
import com.apiintel.security.PlatformUserService;
import com.apiintel.store.RecordStore;
import com.apiintel.store.Rows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Alert rule evaluation over snapshot metrics. Incidents dedup by
 * ruleId|entityKey|episode (unique index); a breach opens PENDING and fires
 * after {@code consecutive} breaching evaluations; recovery needs
 * {@code recovery.consecutive} clean evaluations. Missing data is a distinct
 * incident kind, never merged into error alerts. Maintenance windows and
 * cooldowns record a suppressed incident instead of notifying.
 */
@Service
public class AlertService implements ReportService.SnapshotListener {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;

  private final RecordStore store;
  private final JdbcTemplate db;
  private final PlatformUserService users;
  private final NotifyService notify;

  public AlertService(RecordStore store, JdbcTemplate db, PlatformUserService users,
                      NotifyService notify) {
    this.store = store;
    this.db = db;
    this.users = users;
    this.notify = notify;
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }

  // ---------- rules ----------

  public List<ObjectNode> rules(String username) {
    String ownerId = ownerId(username);
    return store.list("alert-rules").stream()
        .filter(r -> ownerId.equals(r.path("ownerId").asText())).toList();
  }

  public ObjectNode saveRule(ObjectNode input, String username) {
    String ownerId = ownerId(username);
    ObjectNode rule = input.hasNonNull("id")
        ? store.get("alert-rules", input.path("id").asText())
        : J.objectNode();
    if (rule.has("ownerId") && !ownerId.equals(rule.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Rule belongs to another user");
    }
    rule.put("ownerId", ownerId);
    for (String f : List.of("name", "reportId", "metric", "groupBy", "severity")) {
      if (input.hasNonNull(f)) rule.put(f, input.path(f).asText());
    }
    for (String f : List.of("conditions", "recovery", "maintenanceWindows", "destinations")) {
      if (input.has(f)) rule.set(f, input.get(f));
    }
    if (input.hasNonNull("consecutive")) rule.put("consecutive", input.path("consecutive").asInt());
    if (input.hasNonNull("minSamples")) rule.put("minSamples", input.path("minSamples").asInt());
    if (input.hasNonNull("cooldownMinutes")) rule.put("cooldownMinutes", input.path("cooldownMinutes").asInt());
    if (input.hasNonNull("notifyRecovery")) rule.put("notifyRecovery", input.path("notifyRecovery").asBoolean());
    if (!rule.has("conditions") || !rule.path("conditions").isArray() || rule.path("conditions").isEmpty()) {
      throw new IllegalArgumentException("At least one condition is required");
    }
    if (!rule.hasNonNull("consecutive")) rule.put("consecutive", 1);
    return store.save("alert-rules", rule, username);
  }

  public void deleteRule(String id, String username) {
    var rule = store.get("alert-rules", id);
    if (!ownerId(username).equals(rule.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Rule belongs to another user");
    }
    store.delete("alert-rules", id, username);
  }

  // ---------- evaluation ----------

  @Override
  public void onSnapshot(ObjectNode snapshot, String ownerUsername) {
    evaluateAll(snapshot);
  }

  /** Evaluate every rule bound to the snapshot's report. */
  public void evaluateAll(ObjectNode snapshot) {
    String reportId = snapshot.path("reportId").asText();
    String ownerId = snapshot.path("ownerId").asText();
    for (ObjectNode rule : store.list("alert-rules")) {
      if (!reportId.equals(rule.path("reportId").asText())) continue;
      if (!ownerId.equals(rule.path("ownerId").asText())) continue;
      evaluate(rule, snapshot, false);
    }
  }

  /** Result of one rule evaluation — also used by backtest (no side effects). */
  public record EvalResult(boolean breach, String kind, String entityKey, ObjectNode detail) {}

  public EvalResult evaluateRule(ObjectNode rule, ObjectNode snapshot) {
    ArrayNode conditions = (ArrayNode) rule.path("conditions");
    ObjectNode detail = J.objectNode();
    boolean all = true;
    boolean missing = false;
    for (JsonNode c : conditions) {
      boolean ok = evalCondition(c, snapshot, detail);
      if (c.path("type").asText("").startsWith("MISSING") && ok) missing = true;
      all &= ok;
    }
    String entityKey = switch (rule.path("groupBy").asText("none")) {
      case "service" -> snapshot.path("metrics").path(0).path("service").asText("");
      case "environment" -> snapshot.path("metrics").path(0).path("environment").asText("");
      default -> "";
    };
    String kind = missing ? "MISSING_DATA"
        : conditions.toString().contains("CONNECTOR_FAILURE") || conditions.toString().contains("REPORT_FAILURE")
        ? "CONNECTOR_FAILURE" : "SERVICE_ERROR";
    return new EvalResult(all, kind, entityKey, detail);
  }

  private boolean evalCondition(JsonNode c, ObjectNode snapshot, ObjectNode detail) {
    String type = c.path("type").asText();
    JsonNode metric = metricByName(snapshot, c.path("metric").asText(""));
    switch (type) {
      case "ABSOLUTE" -> {
        if (metric == null || !metric.hasNonNull("current")) { detail.put(type, "missing"); return false; }
        return compare(metric.path("current").asDouble(), c.path("op").asText(">"), c.path("value").asDouble());
      }
      case "RATE_CHANGE" -> {
        if (metric == null) return false;
        double v = metric.hasNonNull("ppChange") ? metric.path("ppChange").asDouble()
            : metric.hasNonNull("relativeChange") ? metric.path("relativeChange").asDouble() : Double.NaN;
        if (Double.isNaN(v)) { detail.put(type, "missing"); return false; }
        return compare(v, c.path("op").asText(">="), c.path("value").asDouble());
      }
      case "BASELINE_DEVIATION" -> {
        if (metric == null || !metric.hasNonNull("relativeChange")) { detail.put(type, "missing"); return false; }
        return Math.abs(metric.path("relativeChange").asDouble()) * 100.0 > c.path("pct").asDouble();
      }
      case "NEW_ERROR_SIGNATURE" -> {
        var cur = snapshot.path("series").path("errorSignatures");
        var base = snapshot.path("series").path("baselineErrorSignatures");
        for (JsonNode s : cur) {
          boolean known = false;
          for (JsonNode b : base) if (b.asText().equals(s.asText())) known = true;
          if (!known) { detail.put("newSignature", s.asText()); return true; }
        }
        return false;
      }
      case "MISSING_TRAFFIC" -> {
        if (metric == null || "MISSING".equals(metric.path("status").asText())) {
          detail.put(type, "missing"); return true;
        }
        double min = c.path("minExpected").asDouble(1);
        return !metric.hasNonNull("current") || metric.path("current").asDouble() < min;
      }
      case "CONNECTOR_FAILURE" -> {
        return "MISSING".equals(snapshot.path("completeness").asText());
      }
      case "REPORT_FAILURE" -> {
        return !snapshot.path("errors").isEmpty();
      }
      default -> { detail.put("unknown", type); return false; }
    }
  }

  private JsonNode metricByName(ObjectNode snapshot, String name) {
    for (JsonNode m : snapshot.path("metrics")) {
      if (name.isBlank() || name.equals(m.path("name").asText())) return m;
    }
    return null;
  }

  private boolean compare(double actual, String op, double expected) {
    return switch (op) {
      case ">" -> actual > expected;
      case ">=" -> actual >= expected;
      case "<" -> actual < expected;
      case "<=" -> actual <= expected;
      case "==" -> actual == expected;
      case "!=" -> actual != expected;
      default -> throw new IllegalArgumentException("Unknown op " + op);
    };
  }

  private boolean inMaintenance(ObjectNode rule, Instant at) {
    for (JsonNode w : rule.path("maintenanceWindows")) {
      String start = w.path("start").asText("");
      String end = w.path("end").asText("");
      if (!start.isBlank() && !end.isBlank()) {
        try {
          if (!at.isBefore(Instant.parse(start)) && at.isBefore(Instant.parse(end))) return true;
        } catch (Exception ignored) {}
      }
    }
    return false;
  }

  /** Apply one evaluation result to the incident lifecycle. */
  public ObjectNode evaluate(ObjectNode rule, ObjectNode snapshot, boolean dryRun) {
    EvalResult r = evaluateRule(rule, snapshot);
    String ownerId = rule.path("ownerId").asText();
    String episode = snapshot.path("resolvedWindows").path("current").path("startEpoch").asText(
        String.valueOf(Instant.parse(snapshot.path("evaluatedAt").asText()).getEpochSecond()));
    Instant now = Instant.parse(snapshot.path("evaluatedAt").asText());
    if (dryRun) {
      ObjectNode out = J.objectNode();
      out.put("breach", r.breach());
      out.put("kind", r.kind());
      out.set("detail", r.detail());
      return out;
    }
    ObjectNode open = openIncident(rule.path("id").asText(), r.entityKey());
    if (r.breach()) {
      if (open == null) {
        boolean suppressed = inMaintenance(rule, now);
        if (!suppressed) {
          // cooldown: look back for a recently resolved episode
          Long lastResolved = lastResolvedAt(rule.path("id").asText(), r.entityKey());
          int cooldown = rule.path("cooldownMinutes").asInt(0);
          if (cooldown > 0 && lastResolved != null
              && now.isBefore(Instant.ofEpochSecond(lastResolved).plusSeconds(cooldown * 60L))) {
            suppressed = true;
          }
        }
        String id = UUID.randomUUID().toString();
        int breaches = 1;
        String state = suppressed ? "SUPPRESSED" : (breaches >= rule.path("consecutive").asInt(1) ? "FIRING" : "PENDING");
        db.update("INSERT INTO alert_incidents(id,rule_id,owner_id,entity_key,episode,state,kind,"
            + "opened_at,last_eval_at,consecutive_breaches,consecutive_ok,last_snapshot_id)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            id, rule.path("id").asText(), ownerId, r.entityKey(), episode, state, r.kind(),
            now.toString(), now.toString(), breaches, 0, snapshot.path("id").asText());
        if ("FIRING".equals(state)) fire(rule, r, snapshot, episode, "open");
      } else {
        int breaches = open.path("consecutiveBreaches").asInt() + 1;
        String state = open.path("state").asText();
        String newState = "SUPPRESSED".equals(state) ? state
            : breaches >= rule.path("consecutive").asInt(1) ? "FIRING" : "PENDING";
        db.update("UPDATE alert_incidents SET consecutive_breaches=?,consecutive_ok=0,state=?,"
            + "last_eval_at=?,last_snapshot_id=? WHERE id=?",
            breaches, newState, now.toString(), snapshot.path("id").asText(), open.path("id").asText());
        if (!state.equals("FIRING") && newState.equals("FIRING")) {
          fire(rule, r, snapshot, open.path("episode").asText(), "open");
        }
      }
    } else {
      if (open != null) {
        int ok = open.path("consecutiveOk").asInt() + 1;
        int need = rule.path("recovery").path("consecutive").asInt(1);
        boolean resolve = "FIRING".equals(open.path("state").asText()) && ok >= need;
        db.update("UPDATE alert_incidents SET consecutive_breaches=0,consecutive_ok=?,"
            + "last_eval_at=?,last_snapshot_id=?,state=? WHERE id=?",
            ok, now.toString(), snapshot.path("id").asText(),
            resolve ? "RESOLVED" : ("FIRING".equals(open.path("state").asText()) ? "RECOVERING" : open.path("state").asText()),
            open.path("id").asText());
        if (resolve) {
          db.update("UPDATE alert_incidents SET resolved_at=? WHERE id=?", now.toString(), open.path("id").asText());
          if (rule.path("notifyRecovery").asBoolean(false)) {
            fire(rule, r, snapshot, open.path("episode").asText(), "recovery");
          }
        }
      }
    }
    return J.objectNode().put("breach", r.breach()).put("kind", r.kind());
  }

  private void fire(ObjectNode rule, EvalResult r, ObjectNode snapshot, String episode, String phase) {
    String dedup = rule.path("id").asText() + "|" + r.entityKey() + "|" + episode + "|" + phase;
    ObjectNode payload = J.objectNode();
    payload.put("type", "ALERT_" + phase.toUpperCase());
    payload.put("title", rule.path("name").asText("Alert") + " " + phase);
    payload.put("body", "Rule " + rule.path("name").asText("") + " " + phase
        + " on snapshot " + snapshot.path("id").asText());
    payload.put("ruleId", rule.path("id").asText());
    payload.put("snapshotId", snapshot.path("id").asText());
    payload.put("kind", r.kind());
    var dests = rule.path("destinations");
    if (dests.isArray() && !dests.isEmpty()) {
      for (JsonNode d : dests) {
        notify.enqueue(rule.path("ownerId").asText(), dedup + "|" + d.asText(), d.asText(), payload);
      }
    } else {
      notify.notifyOnce(rule.path("ownerId").asText(), dedup,
          payload.path("title").asText(), payload.path("body").asText());
    }
  }

  private ObjectNode openIncident(String ruleId, String entityKey) {
    return db.query("SELECT * FROM alert_incidents WHERE rule_id=? AND entity_key=?"
        + " AND state IN ('PENDING','FIRING','RECOVERING','SUPPRESSED') ORDER BY opened_at DESC LIMIT 1",
        this::incidentRow, ruleId, entityKey).stream().findFirst().orElse(null);
  }

  private Long lastResolvedAt(String ruleId, String entityKey) {
    return db.query("SELECT resolved_at FROM alert_incidents WHERE rule_id=? AND entity_key=?"
        + " AND state='RESOLVED' ORDER BY resolved_at DESC LIMIT 1",
        (rs, n) -> rs.getString(1), ruleId, entityKey).stream().findFirst()
        .map(Instant::parse).map(Instant::getEpochSecond).orElse(null);
  }

  public List<ObjectNode> incidents(String username) {
    return db.query("SELECT * FROM alert_incidents WHERE owner_id=? ORDER BY opened_at DESC LIMIT 200",
        this::incidentRow, ownerId(username));
  }

  public ObjectNode incident(String id, String username) {
    ObjectNode inc = db.query("SELECT * FROM alert_incidents WHERE id=?", this::incidentRow, id)
        .stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
    if (!ownerId(username).equals(inc.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Incident belongs to another user");
    }
    return inc;
  }

  /** Evaluate a rule against stored snapshots without side effects. */
  public ArrayNode backtest(String ruleId, String username) {
    var rule = store.get("alert-rules", ruleId);
    if (!ownerId(username).equals(rule.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Rule belongs to another user");
    }
    ArrayNode out = J.arrayNode();
    for (Map<String, Object> row : db.queryForList(
        "SELECT * FROM report_snapshots WHERE report_id=? AND owner_id=? ORDER BY evaluated_at LIMIT 50",
        rule.path("reportId").asText(""), rule.path("ownerId").asText())) {
      ObjectNode snap;
      try {
        snap = J.objectNode();
        snap.put("id", Rows.str(row, "id"));
        snap.put("completeness", Rows.str(row, "completeness"));
        snap.put("evaluatedAt", Rows.str(row, "evaluated_at"));
        snap.set("metrics", new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Rows.str(row, "metrics")));
        snap.set("series", new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Rows.str(row, "series")));
        snap.set("resolvedWindows", new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Rows.str(row, "resolved_windows")));
        snap.set("errors", new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Rows.str(row, "errors")));
      } catch (Exception e) {
        continue;
      }
      ObjectNode res = evaluate(rule, snap, true);
      res.put("snapshotId", snap.path("id").asText());
      res.put("evaluatedAt", snap.path("evaluatedAt").asText());
      out.add(res);
    }
    return out;
  }

  private ObjectNode incidentRow(ResultSet rs, int n) throws SQLException {
    ObjectNode o = J.objectNode();
    for (String c : List.of("id", "rule_id", "owner_id", "entity_key", "episode", "state", "kind",
        "opened_at", "last_eval_at", "last_snapshot_id", "resolved_at")) {
      String v = rs.getString(c);
      if (v != null) o.put(camel(c), v);
    }
    o.put("consecutiveBreaches", rs.getInt("consecutive_breaches"));
    o.put("consecutiveOk", rs.getInt("consecutive_ok"));
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
