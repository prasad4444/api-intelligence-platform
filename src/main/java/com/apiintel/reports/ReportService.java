package com.apiintel.reports;

import com.apiintel.compare.*;
import com.apiintel.integrations.ConnectionService;
import com.apiintel.security.PlatformUserService;
import com.apiintel.splunk.SplunkService;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Report definitions (records kind "report-definitions"), on-demand preview,
 * and immutable snapshot execution. A snapshot row is written once into
 * {@code report_snapshots} and never updated — derived knowledge items carry a
 * dependency hash so edits to the report definition invalidate them.
 */
@Service
public class ReportService {

  /** Hook invoked after each snapshot; AlertService registers itself. */
  public interface SnapshotListener {
    void onSnapshot(ObjectNode snapshot, String ownerUsername);
  }

  private static final JsonNodeFactory J = JsonNodeFactory.instance;

  private final RecordStore store;
  private final ConnectionService connections;
  private final PlatformUserService users;
  private final SplunkService splunk;
  private final org.springframework.jdbc.core.JdbcTemplate db;
  private final ObjectMapper json = new ObjectMapper();
  private final List<SnapshotListener> listeners;

  public ReportService(RecordStore store, ConnectionService connections,
                       PlatformUserService users, SplunkService splunk,
                       org.springframework.jdbc.core.JdbcTemplate db,
                       @Autowired(required = false) List<SnapshotListener> listeners) {
    this.store = store;
    this.connections = connections;
    this.users = users;
    this.splunk = splunk;
    this.db = db;
    this.listeners = listeners == null ? List.of() : listeners;
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }

  // ---------- definitions ----------

  public List<ObjectNode> definitions(String username) {
    String ownerId = ownerId(username);
    return store.list("report-definitions").stream()
        .filter(r -> ownerId.equals(r.path("ownerId").asText())).toList();
  }

  public ObjectNode saveDefinition(ObjectNode input, String username) {
    String ownerId = ownerId(username);
    ObjectNode def = input.hasNonNull("id")
        ? store.get("report-definitions", input.path("id").asText())
        : J.objectNode();
    if (def.has("ownerId") && !ownerId.equals(def.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Report belongs to another user");
    }
    def.put("ownerId", ownerId);
    for (String f : List.of("name", "templateId", "savedSearch", "scheduleId")) {
      if (input.hasNonNull(f)) def.put(f, input.path(f).asText());
    }
    for (String f : List.of("connectionIds", "filters", "metrics", "charts", "recipients", "policy")) {
      if (input.has(f)) def.set(f, input.get(f));
    }
    if (def.path("name").asText("").isBlank()) throw new IllegalArgumentException("Report name is required");
    if (!def.has("policy")) def.set("policy", defaultPolicy());
    validatePolicy((ObjectNode) def.path("policy"));
    for (JsonNode c : def.path("connectionIds")) {
      connections.getOwned(c.asText(), username); // owner check per connection
    }
    return store.save("report-definitions", def, username);
  }

  public void deleteDefinition(String id, String username) {
    var def = store.get("report-definitions", id);
    if (!ownerId(username).equals(def.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Report belongs to another user");
    }
    store.delete("report-definitions", id, username);
  }

  public ObjectNode definition(String id, String username) {
    var def = store.get("report-definitions", id);
    if (!ownerId(username).equals(def.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Report belongs to another user");
    }
    return def;
  }

  private ObjectNode defaultPolicy() {
    ObjectNode p = J.objectNode();
    p.put("preset", "LAST_1H");
    p.put("mode", "ROLLING");
    p.put("zone", "UTC");
    p.put("bucketSeconds", 300);
    p.put("lagSeconds", 120);
    p.put("completedBucketsOnly", true);
    p.put("weeks", 1);
    return p;
  }

  public ComparisonPolicy toPolicy(ObjectNode node) {
    ComparisonPolicy p = new ComparisonPolicy();
    p.preset(ComparisonPolicy.Preset.valueOf(node.path("preset").asText("LAST_1H")));
    p.mode(ComparisonPolicy.Mode.valueOf(node.path("mode").asText("ROLLING")));
    p.zone(node.path("zone").asText("UTC"));
    p.bucket(Duration.ofSeconds(node.path("bucketSeconds").asLong(300)));
    p.lag(Duration.ofSeconds(node.path("lagSeconds").asLong(120)));
    p.completedBucketsOnly(node.path("completedBucketsOnly").asBoolean(true));
    p.weeks(node.path("weeks").asInt(1));
    if (node.hasNonNull("customStart")) p.customStart(node.path("customStart").asLong());
    if (node.hasNonNull("customEnd")) p.customEnd(node.path("customEnd").asLong());
    if (node.hasNonNull("customBaselineStart")) p.customBaselineStart(node.path("customBaselineStart").asLong());
    if (node.hasNonNull("customBaselineEnd")) p.customBaselineEnd(node.path("customBaselineEnd").asLong());
    return p;
  }

  private void validatePolicy(ObjectNode node) {
    try {
      toPolicy(node);
      java.time.ZoneId.of(node.path("zone").asText("UTC"));
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw new IllegalArgumentException("Invalid report policy: " + e.getMessage());
    }
  }

  // ---------- execution ----------

  /** Resolve windows and collect stats; returns the snapshot payload (not persisted). */
  public ObjectNode executeReport(ObjectNode def, Instant plannedAt, String username) {
    String ownerId = def.path("ownerId").asText();
    ComparisonPolicy policy = toPolicy((ObjectNode) def.path("policy"));
    ResolvedComparison resolved = new WindowResolver().resolve(policy, plannedAt);

    ObjectNode windows = J.objectNode();
    ObjectNode cur = windows.putObject("current");
    putWindow(cur, resolved.current());
    ArrayNode bl = windows.putArray("baselines");
    resolved.baselines().forEach(w -> putWindow(bl.addObject(), w));
    windows.put("lagSeconds", resolved.lag().getSeconds());
    windows.put("bucketSeconds", resolved.bucket().getSeconds());
    ArrayNode notes = windows.putArray("notes");
    resolved.notes().forEach(notes::add);

    // collect stats per window across every configured connection
    List<String> connectionIds = new ArrayList<>();
    def.path("connectionIds").forEach(c -> connectionIds.add(c.asText()));
    String connectionId = connectionIds.isEmpty() ? "" : connectionIds.get(0);
    ArrayNode errors = J.arrayNode();
    WindowStats currentStats;
    List<WindowStats> baselineStats = new ArrayList<>();
    if (connectionId.isBlank()) {
      currentStats = WindowStats.missing("no connection configured");
      for (int i = 0; i < resolved.baselines().size(); i++) baselineStats.add(WindowStats.missing("no connection"));
    } else {
      // revalidate credentials: auth/permission failures must fail the run so
      // the scheduler can mark the connection and notify the owner.
      try {
        var conn = connections.getOwned(connectionId, username);
        splunk.detectVersion(conn);
      } catch (com.apiintel.splunk.SplunkClient.SplunkException e) {
        if ("SPLUNK_AUTH".equals(e.code) || "SPLUNK_PERMISSION".equals(e.code)) {
          try { connections.markReconnect(connectionId, username, e.getMessage()); } catch (RuntimeException ignored) {}
          throw e;
        }
        // transient errors degrade to MISSING metrics below
      }
      currentStats = splunk.collectStats(connectionId, resolved.current(), username);
      for (Window b : resolved.baselines()) {
        baselineStats.add(splunk.collectStats(connectionId, b, username));
      }
    }
    MetricCalculator calc = new MetricCalculator();
    long baselineSeconds = resolved.baselines().stream().mapToLong(Window::durationSeconds).sum();
    ArrayNode metrics = calc.buildMetrics(currentStats, baselineStats,
        resolved.current().durationSeconds(), baselineSeconds);
    // provenance per metric
    int i = 1;
    for (JsonNode m : metrics) {
      ObjectNode prov = ((ObjectNode) m).putObject("provenance");
      prov.put("metricId", "M" + i++);
      prov.put("connectionId", connectionId);
      prov.put("earliest", resolved.current().start().getEpochSecond());
      prov.put("latest", resolved.current().end().getEpochSecond());
      prov.put("apiVersion", "");
      prov.put("retrievedAt", Instant.now().toString());
    }

    ObjectNode series = J.objectNode();
    ArrayNode sigs = series.putArray("errorSignatures");
    currentStats.exceptions().keySet().stream().sorted().forEach(sigs::add);
    ArrayNode baseSigs = series.putArray("baselineErrorSignatures");
    baselineStats.stream().filter(WindowStats::available)
        .flatMap(w -> w.exceptions().keySet().stream()).distinct().sorted().forEach(baseSigs::add);

    ObjectNode freshness = J.objectNode();
    freshness.put("currentLatest", currentStats.latestTime() == null ? "" : currentStats.latestTime().toString());
    String completeness = currentStats.available()
        ? (resolved.current().partial() ? "PARTIAL" : "COMPLETE") : "MISSING";

    ObjectNode snapshot = J.objectNode();
    snapshot.put("id", UUID.randomUUID().toString());
    snapshot.put("reportId", def.path("id").asText());
    snapshot.put("ownerId", ownerId);
    snapshot.put("reportVersion", def.path("revision").asInt(1));
    snapshot.put("plannedAt", plannedAt.toString());
    snapshot.put("evaluatedAt", resolved.evaluatedAt().toString());
    snapshot.set("resolvedWindows", windows);
    snapshot.set("metrics", metrics);
    snapshot.set("series", series);
    snapshot.set("freshness", freshness);
    snapshot.put("completeness", completeness);
    snapshot.set("errors", errors);
    snapshot.put("aiSummary", "");
    return snapshot;
  }

  private void putWindow(ObjectNode node, Window w) {
    node.put("start", w.start().toString());
    node.put("end", w.end().toString());
    node.put("localStart", w.localStart().toString());
    node.put("localEnd", w.localEnd().toString());
    node.put("zone", w.zone().getId());
    node.put("durationSeconds", w.durationSeconds());
    node.put("partial", w.partial());
    node.put("startEpoch", w.start().getEpochSecond());
    node.put("endEpoch", w.end().getEpochSecond());
  }

  /** Insert one immutable snapshot row and notify listeners (alert rules). */
  public ObjectNode persistSnapshot(ObjectNode snapshot) {
    db.update(
        "INSERT INTO report_snapshots(id,report_id,owner_id,report_version,execution_key,planned_at,"
        + "evaluated_at,resolved_windows,metrics,series,freshness,completeness,errors,ai_summary,created_at)"
        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        snapshot.path("id").asText(), snapshot.path("reportId").asText(),
        snapshot.path("ownerId").asText(), snapshot.path("reportVersion").asInt(),
        snapshot.path("executionKey").asText(snapshot.path("id").asText()),
        snapshot.path("plannedAt").asText(), snapshot.path("evaluatedAt").asText(),
        snapshot.path("resolvedWindows").toString(), snapshot.path("metrics").toString(),
        snapshot.path("series").toString(), snapshot.path("freshness").toString(),
        snapshot.path("completeness").asText(), snapshot.path("errors").toString(),
        snapshot.path("aiSummary").asText(""), Instant.now().toString());
    for (SnapshotListener l : listeners) {
      try { l.onSnapshot(snapshot, snapshot.path("owner").asText("")); } catch (RuntimeException ignored) {}
    }
    return snapshot;
  }

  /** Preview — resolve and compute without persisting a definition or snapshot. */
  public ObjectNode preview(String reportId, ObjectNode override, String username) {
    ObjectNode def;
    if (reportId == null || reportId.isBlank()) {
      def = override.deepCopy();
      def.put("ownerId", ownerId(username));
      def.put("revision", 1);
      if (!def.has("policy")) def.set("policy", defaultPolicy());
      validatePolicy((ObjectNode) def.path("policy"));
      for (JsonNode c : def.path("connectionIds")) {
        connections.getOwned(c.asText(), username);
      }
    } else {
      def = definition(reportId, username).deepCopy();
      if (override != null && override.has("policy")) def.set("policy", override.get("policy"));
    }
    return executeReport(def, Instant.now(), username);
  }

  /** Run — execute and persist an immutable snapshot. */
  public ObjectNode run(String reportId, Instant plannedAt, String executionKey, String username) {
    var def = definition(reportId, username);
    ObjectNode snapshot = executeReport(def, plannedAt, username);
    snapshot.put("owner", username);
    snapshot.put("executionKey", executionKey == null ? def.path("id").asText() + "|manual|" + UUID.randomUUID()
        : executionKey);
    persistSnapshot(snapshot);
    return snapshot;
  }

  // ---------- snapshots ----------

  public List<ObjectNode> snapshots(String reportId, String username) {
    definition(reportId, username);
    return db.query("SELECT * FROM report_snapshots WHERE report_id=? ORDER BY created_at DESC LIMIT 100",
        (rs, n) -> rowToJson(rs), reportId);
  }

  public ObjectNode snapshot(String id, String username) {
    ObjectNode s = db.query("SELECT * FROM report_snapshots WHERE id=?",
        (rs, n) -> rowToJson(rs), id).stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot not found"));
    if (!ownerId(username).equals(s.path("owner_id").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Snapshot belongs to another user");
    }
    return s;
  }

  public ObjectNode snapshotByExecutionKey(String executionKey) {
    return db.query("SELECT * FROM report_snapshots WHERE execution_key=?",
        (rs, n) -> rowToJson(rs), executionKey).stream().findFirst().orElse(null);
  }

  private ObjectNode rowToJson(java.sql.ResultSet rs) throws java.sql.SQLException {
    ObjectNode n = J.objectNode();
    n.put("id", rs.getString("id"));
    n.put("reportId", rs.getString("report_id"));
    n.put("ownerId", rs.getString("owner_id"));
    n.put("reportVersion", rs.getInt("report_version"));
    n.put("executionKey", rs.getString("execution_key"));
    n.put("plannedAt", rs.getString("planned_at"));
    n.put("evaluatedAt", rs.getString("evaluated_at"));
    n.put("completeness", rs.getString("completeness"));
    n.put("aiSummary", rs.getString("ai_summary"));
    n.put("createdAt", rs.getString("created_at"));
    for (String col : List.of("resolved_windows", "metrics", "series", "freshness", "errors")) {
      try { n.set(camel(col), json.readTree(rs.getString(col))); } catch (Exception ignored) {}
    }
    n.put("owner_id", rs.getString("owner_id"));
    return n;
  }

  private String camel(String col) {
    StringBuilder out = new StringBuilder();
    for (String part : col.split("_")) {
      if (out.length() == 0) out.append(part);
      else out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return out.toString();
  }

  /** Drill-down link into Splunk Web for one metric window. */
  public String drillDownUrl(ObjectNode connection, String spl, long earliest, long latest) {
    String web = connection.path("webUrl").asText(connection.path("baseUrl").asText()
        .replaceAll(":\\d+$", ""));
    String app = connection.path("splunkApp").asText("search");
    return web.replaceAll("/+$", "") + "/en-US/app/" + app + "/search?q="
        + URLEncoder.encode(spl, StandardCharsets.UTF_8)
        + "&earliest=" + earliest + "&latest=" + latest;
  }
}
