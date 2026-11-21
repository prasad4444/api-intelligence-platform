package com.apiintel.connectors;

import com.apiintel.alerts.AlertService;
import com.apiintel.investigate.InvestigateService;
import com.apiintel.integrations.ConnectionService;
import com.apiintel.integrations.IntegrationService;
import com.apiintel.jira.JiraService;
import com.apiintel.reports.ReportService;
import com.apiintel.security.PlatformUserService;
import com.apiintel.splunk.SplunkService;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Connector builder backend: versioned declarative connector definitions,
 * health/drift panel, generated-code review + PR proposal, template catalog,
 * service dashboard, correlation-ID search, investigation playbooks and
 * per-user/per-connection cost limits.
 */
@Service
public class ConnectorService {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final RecordStore store;
  private final ConnectionService connections;
  private final PlatformUserService users;
  private final IntegrationService integrations;
  private final JdbcTemplate db;
  private final GithubService github;
  private ReportService reports;
  private AlertService alerts;
  private InvestigateService investigate;
  private JiraService jira;
  private SplunkService splunk;

  @Value("${platform.features.service-dashboard:true}") private boolean fDashboard = true;
  @Value("${platform.features.playbooks:true}") private boolean fPlaybooks = true;
  @Value("${platform.features.correlation-search:true}") private boolean fCorrelation = true;
  @Value("${platform.features.template-catalog:true}") private boolean fCatalog = true;
  @Value("${platform.features.cost-controls:true}") private boolean fCost = true;

  public ConnectorService(RecordStore store, ConnectionService connections,
      PlatformUserService users, IntegrationService integrations, JdbcTemplate db,
      GithubService github) {
    this.store = store;
    this.connections = connections;
    this.users = users;
    this.integrations = integrations;
    this.db = db;
    this.github = github;
  }

  // Late setter injection avoids constructor cycles (splunk→connections→…).
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setReports(ReportService v) { this.reports = v; }
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setAlerts(AlertService v) { this.alerts = v; }
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setInvestigate(InvestigateService v) { this.investigate = v; }
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setJira(JiraService v) { this.jira = v; }
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSplunk(SplunkService v) { this.splunk = v; }

  /** Test hook: wire collaborators directly. */
  public void wire(ReportService reports, AlertService alerts, InvestigateService investigate,
                   JiraService jira, SplunkService splunk) {
    this.reports = reports;
    this.alerts = alerts;
    this.investigate = investigate;
    this.jira = jira;
    this.splunk = splunk;
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }

  private void feature(boolean flag) {
    if (!flag) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Feature disabled");
  }

  // ---------- connector versions ----------

  /** Save a new DRAFT connector version; schemaHash fingerprints the response shape. */
  public ObjectNode saveVersion(String connectionId, ObjectNode input, String username) {
    var connection = connections.getOwned(connectionId, username);
    ObjectNode config = input.path("config").isObject()
        ? (ObjectNode) input.get("config") : input;
    String schemaHash = input.path("schemaHash").asText(
        config.path("responseSchema").isMissingNode() ? ""
            : DriftMonitor.shapeHash(config.get("responseSchema")));
    if (schemaHash.isBlank() && config.has("responseSchema")) {
      schemaHash = DriftMonitor.shapeHash(config.get("responseSchema"));
    }
    int version = (int) store.list("connector-versions").stream()
        .filter(v -> connectionId.equals(v.path("connectionId").asText())).count() + 1;
    var v = J.objectNode();
    v.put("ownerId", connection.path("ownerId").asText());
    v.put("owner", username);
    v.put("connectionId", connectionId);
    v.put("version", version);
    v.put("schemaHash", schemaHash);
    v.put("status", "DRAFT");
    v.set("config", config.deepCopy());
    v.put("createdAt", Instant.now().toString());
    return store.save("connector-versions", v, username);
  }

  public List<ObjectNode> versions(String connectionId, String username) {
    var connection = connections.getOwned(connectionId, username);
    return store.list("connector-versions").stream()
        .filter(v -> connectionId.equals(v.path("connectionId").asText()))
        .toList();
  }

  private ObjectNode ownedVersion(String versionId, String username) {
    var v = store.get("connector-versions", versionId);
    if (!ownerId(username).equals(v.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Version belongs to another user");
    }
    return v;
  }

  public ObjectNode markValidated(String versionId, String username) {
    var v = ownedVersion(versionId, username);
    v.put("status", "VALIDATED");
    v.put("validatedAt", Instant.now().toString());
    return store.save("connector-versions", v, username);
  }

  /** Activate a VALIDATED version; the previous ACTIVE becomes SUPERSEDED. */
  public ObjectNode activate(String versionId, String username) {
    var v = ownedVersion(versionId, username);
    if (!"VALIDATED".equals(v.path("status").asText())) {
      throw new IllegalArgumentException("Only a VALIDATED version can be activated");
    }
    for (ObjectNode other : store.list("connector-versions")) {
      if (v.path("connectionId").asText().equals(other.path("connectionId").asText())
          && "ACTIVE".equals(other.path("status").asText())
          && !other.path("id").asText().equals(versionId)) {
        other.put("status", "SUPERSEDED");
        store.save("connector-versions", other, username);
      }
    }
    v.put("status", "ACTIVE");
    v.put("activatedAt", Instant.now().toString());
    v = store.save("connector-versions", v, username);
    // Propagate config + schema hash onto the connection for execution-time drift checks.
    var connection = connections.getOwned(v.path("connectionId").asText(), username);
    connection.set("connectorConfig", v.path("config").deepCopy());
    if (!v.path("schemaHash").asText("").isBlank()) {
      connection.put("schemaHash", v.path("schemaHash").asText());
    }
    connections.redact(store.save("connections", connection, username));
    return v;
  }

  // ---------- health ----------

  /** Owner's connectors with execution counters, error rate and drift status. */
  public List<ObjectNode> health(String username) {
    String ownerId = ownerId(username);
    var out = new ArrayList<ObjectNode>();
    for (ObjectNode c : store.list("connections")) {
      if (!ownerId.equals(c.path("ownerId").asText())) continue;
      long execs = c.path("executions").asLong(0);
      long fails = c.path("failures").asLong(0);
      var h = J.objectNode();
      h.put("connectionId", c.path("id").asText());
      h.put("name", c.path("name").asText());
      h.put("provider", c.path("provider").asText());
      h.put("state", c.path("state").asText());
      h.put("executions", execs);
      h.put("failures", fails);
      h.put("errorRate", execs + fails == 0 ? 0 : (double) fails / (execs + fails));
      h.put("lastSuccessAt", c.path("lastSuccessAt").asText(""));
      h.put("driftDetected", c.path("driftDetected").asBoolean(false));
      h.put("health", c.path("health").asText(
          c.path("driftDetected").asBoolean(false) ? "DEGRADED" : "OK"));
      out.add(h);
    }
    return out;
  }

  // ---------- codegen ----------

  public ObjectNode codegen(String connectionId, ObjectNode input, String username) {
    var connection = connections.getOwned(connectionId, username);
    String name = input.path("name").asText(connection.path("name").asText("connector"))
        .replaceAll("[^A-Za-z0-9_-]", "-");
    ObjectNode config = input.path("config").isObject()
        ? (ObjectNode) input.get("config") : connection;
    ObjectNode fixture = input.path("fixture").isObject()
        ? (ObjectNode) input.get("fixture") : J.objectNode();
    var generated = Codegen.generate(name, config, fixture);
    generated.put("note", "Review only — generated code is never loaded at runtime");
    return generated;
  }

  public ObjectNode validateGenerated(String connectionId, ObjectNode input, String username) {
    connections.getOwned(connectionId, username);
    ObjectNode config = input.path("config").isObject()
        ? (ObjectNode) input.get("config") : J.objectNode();
    ObjectNode fixture = input.path("fixture").isObject()
        ? (ObjectNode) input.get("fixture") : J.objectNode();
    return Codegen.validateGenerated(config, fixture);
  }

  /** Create a reviewed-write proposal that opens the connector PR on execution. */
  public ObjectNode proposePr(String connectionId, ObjectNode input, String username) {
    connections.getOwned(connectionId, username); // the connector being shipped
    String githubConnectionId = input.path("githubConnectionId").asText("");
    if (githubConnectionId.isBlank()) {
      throw new IllegalArgumentException("githubConnectionId required");
    }
    ArrayNode files = input.path("files").isArray()
        ? (ArrayNode) input.get("files") : J.arrayNode();
    if (files.isEmpty()) throw new IllegalArgumentException("No files to commit");
    var plan = input.deepCopy();
    String shortId = UUID8();
    plan.put("branch", input.path("branch").asText("connector/"
        + input.path("name").asText("connector").replaceAll("[^a-z0-9-]", "-") + "-" + shortId));
    return github.proposeConnectorPr(githubConnectionId, plan, files, username);
  }

  private static String UUID8() {
    return java.util.UUID.randomUUID().toString().substring(0, 8);
  }

  // ---------- template catalog ----------

  public ArrayNode catalog(String username) {
    feature(fCatalog);
    var out = J.arrayNode();
    out.add(seed("error-rate-alert", "Error rate alert",
        "Alert when error rate exceeds a threshold with minimum traffic",
        "{\"preset\":\"LAST_1H\",\"mode\":\"ROLLING\"}",
        "[{\"type\":\"ABSOLUTE\",\"metric\":\"error_rate\",\"op\":\">\",\"value\":0.05},"
            + "{\"type\":\"ABSOLUTE\",\"metric\":\"requests\",\"op\":\">=\",\"value\":200}]"));
    out.add(seed("latency-p95-regression", "Latency p95 regression",
        "Alert when p95 latency regresses vs baseline",
        "{\"preset\":\"LAST_24H\",\"mode\":\"CALENDAR\"}",
        "[{\"type\":\"BASELINE_DEVIATION\",\"metric\":\"latency_p95\",\"op\":\">\",\"value\":0.2}]"));
    out.add(seed("missing-traffic", "Missing traffic",
        "Alert when expected traffic disappears",
        "{\"preset\":\"LAST_15M\",\"mode\":\"ROLLING\"}",
        "[{\"type\":\"MISSING_TRAFFIC\",\"metric\":\"requests\",\"minExpected\":100}]"));
    out.add(seed("weekly-incident-report", "Recurring incident weekly report",
        "Weekly scheduled report of incidents and error signatures",
        "{\"preset\":\"MULTI_WEEK\",\"mode\":\"CALENDAR\",\"weeks\":4}", "[]"));
    return out;
  }

  private ObjectNode seed(String id, String name, String description, String policy, String conditions) {
    var t = J.objectNode();
    t.put("id", id).put("name", name).put("description", description);
    try {
      t.set("policy", JSON.readTree(policy));
      t.set("conditions", JSON.readTree(conditions));
    } catch (Exception ignored) {}
    return t;
  }

  /** Instantiate a report definition (+ alert rule) from a catalog template. */
  public ObjectNode applyCatalog(String templateId, ObjectNode input, String username) {
    feature(fCatalog);
    for (JsonNode t : catalog(username)) {
      if (!templateId.equals(t.path("id").asText())) continue;
      var defIn = J.objectNode().put("name", t.path("name").asText());
      if (input.has("connectionIds")) defIn.set("connectionIds", input.get("connectionIds"));
      defIn.set("policy", t.get("policy").deepCopy());
      var def = reports.saveDefinition(defIn, username);
      var out = J.objectNode();
      out.set("report", def);
      if (!t.path("conditions").isEmpty()) {
        var ruleIn = J.objectNode()
            .put("reportId", def.path("id").asText())
            .put("name", t.path("name").asText());
        ruleIn.set("conditions", t.get("conditions").deepCopy());
        out.set("rule", alerts.saveRule(ruleIn, username));
      }
      return out;
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
  }

  // ---------- service dashboard ----------

  public ObjectNode dashboard(String service, String username) {
    feature(fDashboard);
    String ownerId = ownerId(username);
    var out = J.objectNode();
    var snaps = out.putArray("snapshots");
    for (var row : db.queryForList(
        "SELECT id,report_id,completeness,evaluated_at FROM report_snapshots"
            + " WHERE owner_id=? ORDER BY created_at DESC LIMIT 5", ownerId)) {
      var s = snaps.addObject();
      s.put("id", String.valueOf(row.get("id") == null ? row.get("ID") : row.get("id")));
      s.put("reportId", String.valueOf(row.get("report_id") == null ? row.get("REPORT_ID") : row.get("report_id")));
      s.put("completeness", String.valueOf(row.get("completeness") == null ? row.get("COMPLETENESS") : row.get("completeness")));
      s.put("evaluatedAt", String.valueOf(row.get("evaluated_at") == null ? row.get("EVALUATED_AT") : row.get("evaluated_at")));
    }
    var incidents = out.putArray("incidents");
    for (var row : db.queryForList(
        "SELECT id,rule_id,entity_key,state,kind,opened_at FROM alert_incidents"
            + " WHERE owner_id=? ORDER BY opened_at DESC LIMIT 10", ownerId)) {
      var i = incidents.addObject();
      i.put("id", String.valueOf(row.get("id") == null ? row.get("ID") : row.get("id")));
      i.put("state", String.valueOf(row.get("state") == null ? row.get("STATE") : row.get("state")));
      i.put("kind", String.valueOf(row.get("kind") == null ? row.get("KIND") : row.get("kind")));
      i.put("openedAt", String.valueOf(row.get("opened_at") == null ? row.get("OPENED_AT") : row.get("opened_at")));
    }
    var jiraIssues = out.putArray("jiraIssues");
    if (jira != null && service != null && !service.isBlank()) {
      try {
        for (ObjectNode c : store.list("connections")) {
          if (!ownerId.equals(c.path("ownerId").asText())
              || !"jira".equals(c.path("provider").asText())
              || !"CONNECTED".equals(c.path("state").asText())) continue;
          JsonNode res = jira.search(c.path("id").asText(),
              "text ~ \"" + service.replace("\"", "") + "\"", null, null, username);
          for (JsonNode issue : res.path("issues")) {
            jiraIssues.addObject().put("key", issue.path("key").asText())
                .put("summary", issue.path("fields").path("summary").asText());
          }
          break;
        }
      } catch (RuntimeException ignored) {}
    }
    var contracts = out.putArray("contracts");
    for (ObjectNode spec : store.list("specs")) {
      if (service != null && !service.isBlank()
          && !spec.path("name").asText("").toLowerCase().contains(service.toLowerCase())) continue;
      contracts.addObject().put("id", spec.path("id").asText())
          .put("name", spec.path("name").asText());
      if (contracts.size() >= 10) break;
    }
    out.put("service", service == null ? "" : service);
    return out;
  }

  // ---------- correlation search ----------

  /** Search a correlation id across the owner's Splunk + replayable connectors. */
  public ObjectNode correlation(String correlationId, String username) {
    feature(fCorrelation);
    if (correlationId == null || correlationId.isBlank() || correlationId.length() > 200) {
      throw new IllegalArgumentException("Enter a correlation id");
    }
    String ownerId = ownerId(username);
    var out = J.objectNode();
    out.put("correlationId", correlationId);
    var sources = out.putArray("sources");
    for (ObjectNode c : store.list("connections")) {
      if (!ownerId.equals(c.path("ownerId").asText())
          || !"CONNECTED".equals(c.path("state").asText())) continue;
      String provider = c.path("provider").asText();
      var src = sources.addObject();
      src.put("connectionId", c.path("id").asText());
      src.put("provider", provider);
      try {
        if ("splunk".equals(provider) && splunk != null) {
          String index = c.path("approvedIndexes").isArray() && c.path("approvedIndexes").size() > 0
              ? c.path("approvedIndexes").get(0).asText() : "main";
          String corrField = c.path("fieldMappings").path("correlationId").asText("correlationId");
          long latest = Instant.now().getEpochSecond();
          String spl = "search index=" + index + " " + corrField + "=\""
              + correlationId.replace("\"", "") + "\" | head 20";
          String sid = splunk.createJob(c, spl, latest - 86400, latest);
          var st = splunk.awaitDone(c, sid, 20);
          if ("DONE".equalsIgnoreCase(st.status())) {
            var page = splunk.results(c, sid, 0, 20, 20);
            var arr = src.putArray("events");
            page.results().forEach(arr::add);
          }
          try { splunk.cancelSid(c, sid); } catch (Exception ignored) {}
          src.put("ok", true);
        } else if (!c.path("correlationPath").asText("").isBlank()) {
          var runtime = connections.runtimeConfig(c);
          JsonNode res = integrations.call(runtime, "GET",
              c.path("correlationPath").asText()
                  + (c.path("correlationPath").asText().contains("?") ? "&" : "?")
                  + "correlationId=" + java.net.URLEncoder.encode(correlationId, java.nio.charset.StandardCharsets.UTF_8),
              null, username);
          src.set("result", res);
          src.put("ok", true);
        } else {
          sources.remove(sources.size() - 1);
          continue;
        }
      } catch (RuntimeException e) {
        src.put("ok", false).put("error", e.getMessage() == null ? "search failed" : e.getMessage());
      }
    }
    return out;
  }

  // ---------- playbooks ----------

  public List<ObjectNode> playbooks(String username) {
    feature(fPlaybooks);
    String ownerId = ownerId(username);
    return store.list("playbooks").stream()
        .filter(p -> ownerId.equals(p.path("ownerId").asText())).toList();
  }

  public ObjectNode savePlaybook(ObjectNode input, String username) {
    feature(fPlaybooks);
    String ownerId = ownerId(username);
    var p = input.hasNonNull("id")
        ? store.get("playbooks", input.path("id").asText()) : J.objectNode();
    if (p.has("ownerId") && !ownerId.equals(p.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Playbook belongs to another user");
    }
    p.put("ownerId", ownerId);
    p.put("name", input.path("name").asText(p.path("name").asText("")));
    if (p.path("name").asText().isBlank()) throw new IllegalArgumentException("Name required");
    var steps = input.path("steps");
    if (!steps.isArray() || steps.isEmpty() || steps.size() > 20) {
      throw new IllegalArgumentException("1-20 ordered steps required");
    }
    for (JsonNode s : steps) {
      if (!List.of("RUN_TEMPLATE", "FETCH_EVENTS", "JIRA_JQL", "GITHUB_COMMITS")
          .contains(s.path("type").asText(""))) {
        throw new IllegalArgumentException("Unsupported step type " + s.path("type").asText());
      }
    }
    p.set("steps", steps.deepCopy());
    return store.save("playbooks", p, username);
  }

  /** Execute a playbook's steps into an investigation's evidence list. */
  public ObjectNode runPlaybook(String investigationId, String playbookId, String username) {
    feature(fPlaybooks);
    var playbook = store.get("playbooks", playbookId);
    if (!ownerId(username).equals(playbook.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Playbook belongs to another user");
    }
    var inv = investigate.get(investigationId, username);
    ArrayNode evidence = (ArrayNode) inv.path("evidence");
    int next = evidence.size() + 1;
    var ran = J.arrayNode();
    for (JsonNode step : playbook.path("steps")) {
      String type = step.path("type").asText();
      var item = evidence.addObject();
      item.put("id", "E" + next++);
      item.put("type", "PLAYBOOK_" + type);
      try {
        switch (type) {
          case "RUN_TEMPLATE" -> {
            String spl = step.path("spl").asText("");
            String connId = step.path("connectionId").asText("");
            var conn = connections.getOwned(connId, username);
            long latest = Instant.now().getEpochSecond();
            String sid = splunk.createJob(conn, spl, latest - 3600, latest);
            var st = splunk.awaitDone(conn, sid, 30);
            var page = "DONE".equalsIgnoreCase(st.status())
                ? splunk.results(conn, sid, 0, 10, 10) : null;
            try { splunk.cancelSid(conn, sid); } catch (Exception ignored) {}
            item.put("summary", "Splunk template results");
            var arr = item.putObject("data").putArray("rows");
            if (page != null) page.results().forEach(arr::add);
          }
          case "FETCH_EVENTS" -> {
            String connId = step.path("connectionId").asText("");
            var events = splunk.eventsForInvestigation(connId, inv, 10, username);
            item.put("summary", "Splunk events");
            var arr = item.putObject("data").putArray("events");
            events.forEach(arr::add);
          }
          case "JIRA_JQL" -> {
            String connId = step.path("connectionId").asText("");
            JsonNode res = jira.search(connId, step.path("jql").asText(""), null, null, username);
            item.put("summary", "Jira " + res.path("issues").size() + " issues");
            var arr = item.putObject("data").putArray("issues");
            res.path("issues").forEach(i -> arr.addObject()
                .put("key", i.path("key").asText())
                .put("summary", i.path("fields").path("summary").asText()));
          }
          case "GITHUB_COMMITS" -> {
            String connId = step.path("connectionId").asText("");
            var conn = connections.getOwned(connId, username);
            var runtime = connections.runtimeConfig(conn);
            JsonNode res = integrations.call(runtime, "GET",
                "/repos/" + step.path("repo").asText("").replaceAll("[^A-Za-z0-9_./-]", "")
                    + "/commits?per_page=5", null, username);
            item.put("summary", "Recent commits " + step.path("repo").asText());
            var arr = item.putObject("data").putArray("commits");
            for (JsonNode commit : res) {
              arr.addObject().put("sha", commit.path("sha").asText(""))
                  .put("message", commit.path("commit").path("message").asText(""));
            }
          }
          default -> item.put("summary", "unknown step");
        }
        item.put("ok", true);
      } catch (RuntimeException e) {
        item.put("summary", type + " failed").put("ok", false)
            .put("data", J.objectNode().put("error",
                e.getMessage() == null ? "step failed" : e.getMessage()));
      }
      ran.add(item.path("id").asText());
    }
    inv.set("evidence", evidence);
    inv.put("evidenceBytes", evidence.toString().length());
    investigate.saveForPlaybook(inv, username);
    var out = J.objectNode();
    out.set("evidenceIds", ran);
    out.put("evidence", evidence.size());
    return out;
  }

  // ---------- cost limits ----------

  public List<ObjectNode> costLimits(String username) {
    feature(fCost);
    String ownerId = ownerId(username);
    return store.list("cost-limits").stream()
        .filter(c -> ownerId.equals(c.path("ownerId").asText())).toList();
  }

  public ObjectNode saveCostLimit(ObjectNode input, String username) {
    feature(fCost);
    String ownerId = ownerId(username);
    var c = input.hasNonNull("id")
        ? store.get("cost-limits", input.path("id").asText()) : J.objectNode();
    if (c.has("ownerId") && !ownerId.equals(c.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Limit belongs to another user");
    }
    c.put("ownerId", ownerId);
    String scope = input.path("scope").asText(c.path("scope").asText("user"));
    if (!List.of("user", "connection").contains(scope)) {
      throw new IllegalArgumentException("scope must be user or connection");
    }
    c.put("scope", scope);
    String targetId = input.path("targetId").asText(
        "user".equals(scope) ? ownerId : input.path("targetId").asText(""));
    if ("connection".equals(scope)) {
      connections.getOwned(targetId, username); // must own the connection
    }
    c.put("targetId", targetId);
    var limits = input.path("limits").isObject()
        ? (ObjectNode) input.get("limits") : J.objectNode();
    for (String key : List.of("maxWindowHours", "maxConcurrentJobs", "maxResults",
        "retentionDays", "aiContextBytes")) {
      if (limits.has(key) && limits.path(key).asInt(-1) < 0) {
        throw new IllegalArgumentException("Limit " + key + " must be a positive number");
      }
    }
    c.set("limits", limits);
    return store.save("cost-limits", c, username);
  }

  public void deleteCostLimit(String id, String username) {
    feature(fCost);
    var c = store.get("cost-limits", id);
    if (!ownerId(username).equals(c.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Limit belongs to another user");
    }
    store.delete("cost-limits", id, username);
  }
}
