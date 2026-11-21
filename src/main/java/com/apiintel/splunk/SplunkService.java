package com.apiintel.splunk;

import com.apiintel.activity.ActivityLog;
import com.apiintel.compare.WindowStats;
import com.apiintel.integrations.ConnectionService;
import com.apiintel.security.PlatformUserService;
import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Splunk connector service: config validation, version-aware search jobs,
 * saved searches, templates and field-mapping preview.
 *
 * <p><b>API version selection.</b> Splunk's "Search endpoint descriptions"
 * document (help.splunk.com, section "Semantic API versioning") ships a v2
 * search-jobs API starting with Enterprise 9.0.1 and Splunk Cloud 9.0.2208.
 * v2 uses flat endpoints ({@code /services/search/v2/jobs/{sid}/results})
 * while v1 hangs everything under the namespace. This service detects the
 * deployment version via {@code /services/server/info} once, stores the
 * resolved {@code apiVersion} on the connection, and falls back to v1
 * endpoints when a v2 call returns 404 (older builds that report a 9.x
 * version but lack v2 routes). v1 {@code /export} is never used: it streams
 * unbounded results, defeating our pagination caps.
 */
@Service
public class SplunkService {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;
  private static final Pattern DANGEROUS = Pattern.compile(
      "\\|\\s*(delete|outputlookup|collect|sendemail|script|run|map)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern INDEX_TOKEN = Pattern.compile(
      "\\bindex\\s*=\\s*([\"']?)([\\w.*-]+)\\1", Pattern.CASE_INSENSITIVE);
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");
  private static final List<String> MAPPING_KEYS = List.of(
      "timestamp", "service", "environment", "severity", "httpStatus",
      "durationMs", "transactionType", "correlationId", "exception");
  private static final Pattern SECRET_FIELD = Pattern.compile(
      "(?i).*(secret|token|password|passwd|authorization|cookie|session|api[_-]?key).*");

  private final RecordStore store;
  private final ConnectionService connections;
  private final PlatformUserService users;
  private final Vault vault;
  private final SplunkClient client;
  private final ObjectMapper json = new ObjectMapper();
  private final ConcurrentHashMap<String, Semaphore> concurrency = new ConcurrentHashMap<>();

  public SplunkService(RecordStore store, ConnectionService connections,
                       PlatformUserService users, Vault vault, SplunkClient client) {
    this.store = store;
    this.connections = connections;
    this.users = users;
    this.vault = vault;
    this.client = client;
  }

  // ---------- config ----------

  /**
   * Effective numeric limit for a connection: the connection's configured
   * limit capped by any matching cost-limits records (scope=connection by id
   * or scope=user by owner).
   */
  public int limit(ObjectNode connection, String name, int fallback) {
    int value = connection.path("limits").path(name).asInt(fallback);
    for (var cl : store.list("cost-limits")) {
      boolean applies = "connection".equals(cl.path("scope").asText())
          ? cl.path("targetId").asText().equals(connection.path("id").asText())
          : cl.path("targetId").asText().equals(connection.path("ownerId").asText());
      int cap = cl.path("limits").path(name).asInt(-1);
      if (applies && cap > 0) value = Math.min(value, cap);
    }
    return value;
  }

  public List<String> approvedIndexes(ObjectNode connection) {
    List<String> out = new ArrayList<>();
    connection.path("approvedIndexes").forEach(n -> out.add(n.asText()));
    return out;
  }

  public ObjectNode splunkConnection(String connectionId, String username) {
    var connection = connections.getOwned(connectionId, username);
    if (!"splunk".equals(connection.path("provider").asText())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a Splunk connection");
    }
    connections.runtimeConfig(connection);
    return connection;
  }

  // ---------- version ----------

  /** Detect the Splunk build version and persist the resolved apiVersion. */
  public ObjectNode detectVersion(ObjectNode connection) {
    String body = client.get("/services/server/info",
        connection, Map.of("output_mode", "json"));
    String version;
    try {
      version = json.readTree(body).path("entry").path(0).path("content")
          .path("version").asText("");
      if (version.isBlank()) version = json.readTree(body).path("generator").path("version").asText("");
    } catch (Exception e) {
      throw new SplunkClient.SplunkException(502, "SPLUNK_ERROR", "server/info unreadable");
    }
    boolean cloud = "CLOUD".equalsIgnoreCase(connection.path("deployment").asText("ENTERPRISE"));
    String floor = cloud ? "9.0.2208" : "9.0.1";
    String apiVersion = SplunkClient.atLeast(version, floor) ? "v2" : "v1";
    ObjectNode out = J.objectNode();
    out.put("version", version);
    out.put("apiVersion", apiVersion);
    out.put("deployment", cloud ? "CLOUD" : "ENTERPRISE");
    return out;
  }

  // ---------- validation ----------

  public ObjectNode validate(String connectionId, String username) {
    var connection = splunkConnection(connectionId, username);
    ObjectNode result = J.objectNode();
    ObjectNode auth = result.putObject("authenticate");
    ObjectNode version;
    try {
      version = detectVersion(connection);
      connection.put("apiVersion", version.path("apiVersion").asText());
      connection.put("detectedVersion", version.path("version").asText());
    } catch (SplunkClient.SplunkException e) {
      version = J.objectNode().put("version", "").put("apiVersion", "v1");
    }
    result.put("version", version.path("version").asText());
    result.put("apiVersion", version.path("apiVersion").asText("v1"));
    ObjectNode quotas = result.putObject("quotas");
    try {
      JsonNode ctx = json.readTree(client.get(
          "/services/authentication/current-context", connection, Map.of("output_mode", "json")));
      auth.put("ok", true);
      auth.set("identity", ctx.path("entry").path(0).path("content").path("username").isMissingNode()
          ? ctx.path("entry").path(0).path("content") : ctx);
      auth.putNull("error");
      JsonNode caps = ctx.path("entry").path(0).path("content").path("capabilities");
      for (JsonNode role : ctx.path("entry").path(0).path("content").path("roles")) {
        quotas.set(role.asText(), caps == null ? J.objectNode() : caps);
      }
      JsonNode content = ctx.path("entry").path(0).path("content");
      for (String q : List.of("srchJobsQuota", "srchDiskQuota")) {
        if (content.has(q)) quotas.set(q, content.get(q));
      }
    } catch (Exception e) {
      auth.put("ok", false);
      auth.putNull("identity");
      auth.put("error", e.getMessage());
    }
    List<String> indexes = approvedIndexes(connection);
    ObjectNode search = result.putObject("search");
    ObjectNode retrieve = result.putObject("retrieve");
    String idx = indexes.isEmpty() ? "main" : indexes.get(0);
    search.put("index", idx);
    long latest = Instant.now().getEpochSecond();
    long earliest = latest - 900;
    try {
      String spl = "search index=" + idx + " | head 1";
      String sid = createJob(connection, spl, earliest, latest);
      JobState state = awaitDone(connection, sid, 30);
      if (!"DONE".equals(state.status)) {
        search.put("ok", false);
        search.put("error", "search did not finish: " + state.status);
        retrieve.put("ok", false).put("rows", 0).putNull("error");
      } else {
        search.put("ok", true).putNull("error");
        var rows = results(connection, sid, 0, 1, limit(connection, "maxResults", 50000));
        retrieve.put("ok", true).put("rows", rows.results.size()).putNull("error");
        try { cancelSid(connection, sid); } catch (Exception ignored) {}
      }
    } catch (Exception e) {
      search.put("ok", false).put("error", e.getMessage());
      retrieve.put("ok", false).put("rows", 0).put("error", e.getMessage());
    }
    connection.put("state", auth.path("ok").asBoolean() ? "CONNECTED" : "ERROR");
    store.save("connections", connection, username);
    return result;
  }

  // ---------- saved searches ----------

  public ArrayNode savedSearches(String connectionId, String username, int count) {
    var connection = splunkConnection(connectionId, username);
    String body = client.get(client.namespace(connection) + "/saved/searches",
        connection, Map.of("output_mode", "json", "count", String.valueOf(Math.min(count, 100))));
    ArrayNode out = J.arrayNode();
    try {
      for (JsonNode e : json.readTree(body).path("entry")) {
        ObjectNode s = out.addObject();
        s.put("name", e.path("name").asText());
        s.put("search", e.path("content").path("search").asText());
        s.put("qualifiedPath", e.path("content").path("eai:acl").path("app").asText());
      }
    } catch (Exception e) {
      throw new SplunkClient.SplunkException(502, "SPLUNK_ERROR", "saved/searches unreadable");
    }
    return out;
  }

  // ---------- templates ----------

  public List<ObjectNode> templates(String username) {
    String ownerId = ownerId(username);
    return store.list("splunk-templates").stream()
        .filter(t -> ownerId.equals(t.path("ownerId").asText())).toList();
  }

  public ObjectNode saveTemplate(ObjectNode input, String username) {
    String ownerId = ownerId(username);
    ObjectNode template = input.hasNonNull("id")
        ? store.get("splunk-templates", input.path("id").asText())
        : J.objectNode();
    if (template.has("ownerId") && !ownerId.equals(template.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Template belongs to another user");
    }
    template.put("ownerId", ownerId);
    template.put("name", input.path("name").asText(template.path("name").asText("")));
    if (input.hasNonNull("connectionId")) template.put("connectionId", input.path("connectionId").asText());
    if (input.hasNonNull("spl")) template.put("spl", input.path("spl").asText());
    if (input.has("params")) template.set("params", input.get("params"));
    validateTemplate(template, username);
    return store.save("splunk-templates", template, username);
  }

  public void deleteTemplate(String id, String username) {
    var template = store.get("splunk-templates", id);
    if (!ownerId(username).equals(template.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Template belongs to another user");
    }
    store.delete("splunk-templates", id, username);
  }

  /** Enforce approved indexes + banned commands on the template SPL. */
  public void validateTemplate(ObjectNode template, String username) {
    String spl = template.path("spl").asText();
    if (spl.isBlank()) throw new IllegalArgumentException("Template SPL is required");
    if (DANGEROUS.matcher(spl).find()) {
      throw new IllegalArgumentException("SPL uses a blocked command (delete/outputlookup/collect/sendemail/script/run/map)");
    }
    String connectionId = template.path("connectionId").asText("");
    List<String> allowed = connectionId.isBlank() ? List.of()
        : approvedIndexes(connections.getOwned(connectionId, username));
    if (allowed.isEmpty()) {
      // no explicit approval list — require explicit indexes to be declared later
      return;
    }
    Matcher m = INDEX_TOKEN.matcher(spl);
    boolean found = false;
    while (m.find()) {
      found = true;
      if (!allowed.contains(m.group(2))) {
        throw new IllegalArgumentException("Index '" + m.group(2) + "' is not in approvedIndexes");
      }
    }
    if (!found) throw new IllegalArgumentException("Template must scope to an approved index");
  }

  /** Render {{param}} placeholders with typed, allow-listed values. */
  public String renderTemplate(ObjectNode template, ObjectNode params) {
    String spl = template.path("spl").asText();
    ObjectNode specs = (ObjectNode) template.path("params");
    Matcher m = PLACEHOLDER.matcher(spl);
    StringBuffer out = new StringBuffer();
    while (m.find()) {
      String name = m.group(1);
      String value = params == null ? "" : params.path(name).asText("");
      ObjectNode spec = specs.has(name) && specs.path(name).isObject() ? (ObjectNode) specs.path(name) : null;
      if (spec != null && spec.has("allow") && spec.path("allow").isArray()) {
        List<String> allowed = new ArrayList<>();
        spec.path("allow").forEach(a -> allowed.add(a.asText()));
        if (!allowed.contains(value)) {
          throw new IllegalArgumentException("Param '" + name + "' must be one of the allowed values");
        }
      }
      String type = spec != null ? spec.path("type").asText("string") : "string";
      switch (type) {
        case "number" -> { if (!value.matches("-?\\d+(\\.\\d+)?")) throw new IllegalArgumentException("Param '" + name + "' must be numeric"); }
        case "epoch" -> { if (!value.matches("\\d+")) throw new IllegalArgumentException("Param '" + name + "' must be epoch seconds"); }
        default -> value = value.replace("\\", "\\\\").replace("\"", "\\\"");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  // ---------- jobs ----------

  public ObjectNode createJob(String connectionId, ObjectNode input, String username) {
    var connection = splunkConnection(connectionId, username);
    String ownerId = connection.path("ownerId").asText();
    int max = limit(connection, "maxConcurrentJobs", 2);
    Semaphore sem = concurrency.computeIfAbsent(connectionId, k -> new Semaphore(max));
    if (!sem.tryAcquire()) {
      ObjectNode err = J.objectNode().put("code", "CONCURRENCY_LIMIT")
          .put("message", "Concurrency limit reached for this connection");
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, err.toString());
    }
    long earliest = input.path("earliest").asLong();
    long latest = input.path("latest").asLong();
    if (latest <= earliest) {
      sem.release();
      throw new IllegalArgumentException("latest must be after earliest (epoch seconds, half-open)");
    }
    long maxWindow = limit(connection, "maxWindowHours", 720) * 3600L;
    if (latest - earliest > maxWindow) {
      sem.release();
      throw new IllegalArgumentException("Window exceeds maxWindowHours");
    }
    String spl;
    try {
      if (input.hasNonNull("templateId")) {
        var template = store.get("splunk-templates", input.path("templateId").asText());
        if (!ownerId.equals(template.path("ownerId").asText())) {
          throw new org.springframework.security.access.AccessDeniedException("Template belongs to another user");
        }
        validateTemplate(template, username);
        spl = renderTemplate(template, (ObjectNode) input.path("params"));
      } else if (input.hasNonNull("savedSearch")) {
        spl = "| savedsearch \"" + input.path("savedSearch").asText().replace("\"", "") + "\"";
      } else {
        throw new IllegalArgumentException("Provide templateId or savedSearch");
      }
      String sid = createJob(connection, spl, earliest, latest);
      ObjectNode job = J.objectNode();
      job.put("id", java.util.UUID.randomUUID().toString());
      job.put("sid", sid);
      job.put("ownerId", ownerId);
      job.put("connectionId", connectionId);
      job.put("spl", spl);
      job.put("earliest", earliest);
      job.put("latest", latest);
      job.put("apiVersion", connection.path("apiVersion").asText("v2"));
      job.put("state", "RUNNING");
      job.put("createdAt", Instant.now().toString());
      job.put("semaphoreAcquired", true);
      return store.save("splunk-jobs", job, username).put("jobId", job.path("id").asText());
    } catch (SplunkClient.SplunkException e) {
      sem.release();
      throw e;
    } catch (RuntimeException e) {
      sem.release();
      throw e;
    }
  }

  /** Low-level job create honoring version routing + v2→v1 fallback on 404. */
  public String createJob(ObjectNode connection, String spl, long earliest, long latest) {
    String apiVersion = connection.path("apiVersion").asText("");
    if (apiVersion.isBlank()) {
      apiVersion = detectVersion(connection).path("apiVersion").asText("v1");
      connection.put("apiVersion", apiVersion);
    }
    String ns = client.namespace(connection);
    Map<String, String> form = new LinkedHashMap<>();
    form.put("search", spl);
    form.put("earliest_time", String.valueOf(earliest));
    form.put("latest_time", String.valueOf(latest));
    form.put("output_mode", "json");
    String path = "v2".equals(apiVersion) ? ns + "/search/v2/jobs" : ns + "/search/jobs";
    SplunkClient.RawResponse res = rawPost(connection, path, form);
    if (res.status() == 404 && "v2".equals(apiVersion)) {
      res = rawPost(connection, ns + "/search/jobs", form);
      connection.put("apiVersion", "v1");
    }
    if (res.status() >= 400) {
      throw mapError(res);
    }
    try {
      JsonNode body = json.readTree(res.body());
      String sid = findSid(body);
      if (sid.isBlank()) throw new SplunkClient.SplunkException(502, "SPLUNK_ERROR", "job create returned no sid");
      return sid;
    } catch (SplunkClient.SplunkException e) {
      throw e;
    } catch (Exception e) {
      throw new SplunkClient.SplunkException(502, "SPLUNK_ERROR", "job create response unreadable");
    }
  }

  private String findSid(JsonNode node) {
    if (node.hasNonNull("sid")) return node.path("sid").asText();
    for (JsonNode e : node.path("entry")) {
      String sid = e.path("content").path("sid").asText(e.path("name").asText(""));
      if (!sid.isBlank()) return sid;
    }
    return "";
  }

  /** POST form; request() throws on 401/403/503 and returns RawResponse otherwise. */
  private SplunkClient.RawResponse rawPost(ObjectNode connection, String path, Map<String, String> form) {
    return client.request("POST", path, null, form, connection, true);
  }

  private SplunkClient.SplunkException mapError(SplunkClient.RawResponse res) {
    String msg = client.extractMessage(res.body());
    return switch (res.status()) {
      case 401 -> new SplunkClient.SplunkException(401, "SPLUNK_AUTH", "Splunk authentication failed");
      case 403 -> new SplunkClient.SplunkException(403, "SPLUNK_PERMISSION",
          "Splunk permission denied (check index/capability access): " + msg);
      case 503 -> new SplunkClient.SplunkException(503, "SPLUNK_QUOTA", "Splunk quota exceeded: " + msg);
      default -> new SplunkClient.SplunkException(res.status(), "SPLUNK_ERROR",
          "Splunk error " + res.status() + ": " + msg);
    };
  }

  public record JobState(String status, double progress, long resultCount, boolean failed,
                         ArrayNode messages) {}

  public JobState statusSid(ObjectNode connection, String sid, String apiVersion) {
    String ns = client.namespace(connection);
    String path = "v2".equals(apiVersion)
        ? ns + "/search/v2/jobs/" + sid : ns + "/search/jobs/" + sid;
    SplunkClient.RawResponse res = rawGet(connection, path);
    if (res.status() == 404 && "v2".equals(apiVersion)) {
      res = rawGet(connection, ns + "/search/jobs/" + sid);
    }
    if (res.status() >= 400) throw mapError(res);
    try {
      JsonNode body = json.readTree(res.body());
      JsonNode content = body.path("entry").isArray() && !body.path("entry").isEmpty()
          ? body.path("entry").get(0).path("content") : body;
      ArrayNode messages = J.arrayNode();
      content.path("messages").forEach(m -> messages.add(m.path("text").asText(m.asText())));
      String state = content.path("dispatchState").asText(
          content.path("isDone").asBoolean(false) ? "DONE" : "RUNNING");
      return new JobState(state, content.path("doneProgress").asDouble(0),
          content.path("resultCount").asLong(0),
          content.path("isFailed").asBoolean(false)
              || "FAILED".equalsIgnoreCase(state), messages);
    } catch (Exception e) {
      throw new SplunkClient.SplunkException(502, "SPLUNK_ERROR", "job status unreadable");
    }
  }

  private SplunkClient.RawResponse rawGet(ObjectNode connection, String path) {
    return client.request("GET", path, Map.of("output_mode", "json"), null, connection, true);
  }

  public record ResultPage(List<ObjectNode> results, long total, boolean truncated, long offset) {}

  public ResultPage results(ObjectNode connection, String sid, long offset, long count, long maxResults) {
    String apiVersion = connection.path("apiVersion").asText("v2");
    String path = "v2".equals(apiVersion)
        ? "/services/search/v2/jobs/" + sid + "/results"
        : client.namespace(connection) + "/search/jobs/" + sid + "/results";
    List<ObjectNode> rows = new ArrayList<>();
    long pageSize = Math.min(count, limit(connection, "pageSize", 1000));
    long fetched = 0;
    boolean truncated = false;
    while (fetched < count) {
      Map<String, String> q = new LinkedHashMap<>();
      q.put("output_mode", "json");
      q.put("offset", String.valueOf(offset + fetched));
      q.put("count", String.valueOf(Math.min(pageSize, count - fetched)));
      Map<String, String> query = new LinkedHashMap<>(q);
      SplunkClient.RawResponse res = client.request("GET", path, query, null, connection, true);
      if (res.status() >= 400) throw mapError(res);
      try {
        JsonNode body = json.readTree(res.body());
        JsonNode arr = body.path("results");
        if (!arr.isArray() || arr.isEmpty()) break;
        for (JsonNode r : arr) rows.add((ObjectNode) r);
        fetched += arr.size();
        if (arr.size() < pageSize) break;
        if (offset + fetched >= maxResults) { truncated = true; break; }
      } catch (SplunkClient.SplunkException e) {
        throw e;
      } catch (Exception e) {
        throw new SplunkClient.SplunkException(502, "SPLUNK_ERROR", "results unreadable");
      }
    }
    if (offset + fetched >= maxResults) truncated = true;
    return new ResultPage(rows, offset + fetched, truncated, offset);
  }

  public JobState awaitDone(ObjectNode connection, String sid, int maxSeconds) {
    long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
    while (System.currentTimeMillis() < deadline) {
      JobState s = statusSid(connection, sid, connection.path("apiVersion").asText("v2"));
      if ("DONE".equalsIgnoreCase(s.status()) || s.failed()) return s;
      try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return s; }
    }
    return statusSid(connection, sid, connection.path("apiVersion").asText("v2"));
  }

  public void cancelSid(ObjectNode connection, String sid) {
    String apiVersion = connection.path("apiVersion").asText("v2");
    String ns = client.namespace(connection);
    String path = ("v2".equals(apiVersion) ? ns + "/search/v2/jobs/" : ns + "/search/jobs/")
        + sid + "/control";
    Map<String, String> form = Map.of("action", "cancel", "output_mode", "json");
    SplunkClient.RawResponse res = client.request("POST", path, null, form, connection, true);
    if (res.status() >= 400 && res.status() != 404) throw mapError(res);
  }

  // ---------- controller-facing job ops ----------

  private ObjectNode ownedJob(String jobId, String username) {
    var job = store.get("splunk-jobs", jobId);
    if (!ownerId(username).equals(job.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Job belongs to another user");
    }
    return job;
  }

  public ObjectNode jobStatus(String jobId, String username) {
    var job = ownedJob(jobId, username);
    var connection = connections.getOwned(job.path("connectionId").asText(), username);
    try {
      JobState s = statusSid(connection, job.path("sid").asText(), job.path("apiVersion").asText("v2"));
      job.put("state", s.failed() ? "FAILED" : s.status());
      job.put("progress", s.progress());
      job.put("resultCount", s.resultCount());
      store.save("splunk-jobs", job, username);
      if (s.failed() || "DONE".equalsIgnoreCase(s.status())) release(connection);
      ObjectNode out = J.objectNode();
      out.put("jobId", job.path("id").asText());
      out.put("sid", job.path("sid").asText());
      out.put("state", job.path("state").asText());
      out.put("progress", s.progress());
      out.put("resultCount", s.resultCount());
      out.set("messages", s.messages());
      return out;
    } catch (SplunkClient.SplunkException e) {
      release(connection);
      throw e;
    }
  }

  public ObjectNode jobResults(String jobId, long offset, long count, String username) {
    var job = ownedJob(jobId, username);
    var connection = connections.getOwned(job.path("connectionId").asText(), username);
    int max = limit(connection, "maxResults", 50000);
    count = Math.min(count, limit(connection, "pageSize", 1000) * 50L);
    ResultPage page = results(connection, job.path("sid").asText(), offset, count, max);
    ObjectNode out = J.objectNode();
    ArrayNode arr = out.putArray("results");
    page.results().forEach(arr::add);
    out.put("offset", page.offset());
    out.put("count", page.results().size());
    out.put("truncated", page.truncated());
    out.put("maxResults", max);
    return out;
  }

  public ObjectNode cancelJob(String jobId, String username) {
    var job = ownedJob(jobId, username);
    var connection = connections.getOwned(job.path("connectionId").asText(), username);
    cancelSid(connection, job.path("sid").asText());
    release(connection);
    job.put("state", "CANCELLED");
    job = store.save("splunk-jobs", job, username);
    return J.objectNode().put("jobId", job.path("id").asText()).put("state", "CANCELLED");
  }

  private void release(ObjectNode connection) {
    Semaphore s = concurrency.get(connection.path("id").asText());
    if (s != null) s.release();
  }

  // ---------- mapping preview ----------

  public ObjectNode mappingPreview(String connectionId, ObjectNode input, String username) {
    var connection = splunkConnection(connectionId, username);
    String index = input.path("index").asText(approvedIndexes(connection).stream().findFirst().orElse("main"));
    if (!approvedIndexes(connection).isEmpty() && !approvedIndexes(connection).contains(index)) {
      throw new IllegalArgumentException("Index '" + index + "' is not in approvedIndexes");
    }
    long latest = Instant.now().getEpochSecond();
    String spl = "search index=" + index + " earliest=-15m latest=now | head 20";
    String sid = createJob(connection, spl, latest - 900, latest);
    JobState state = awaitDone(connection, sid, limit(connection, "maxJobSeconds", 300) > 60 ? 30 : limit(connection, "maxJobSeconds", 300));
    if (!"DONE".equalsIgnoreCase(state.status())) {
      try { cancelSid(connection, sid); } catch (Exception ignored) {}
      throw new SplunkClient.SplunkException(504, "SPLUNK_ERROR", "mapping preview did not finish");
    }
    ResultPage page = results(connection, sid, 0, 20, limit(connection, "maxResults", 50000));
    try { cancelSid(connection, sid); } catch (Exception ignored) {}
    ArrayNode rows = J.arrayNode();
    ObjectNode mappings = connection.path("fieldMappings").isObject()
        ? (ObjectNode) connection.path("fieldMappings") : J.objectNode();
    for (ObjectNode raw : page.results()) {
      ObjectNode row = rows.addObject();
      ObjectNode mapped = row.putObject("mapped");
      ObjectNode sanitized = row.putObject("raw");
      raw.fields().forEachRemaining(e -> {
        if (!SECRET_FIELD.matcher(e.getKey()).matches()) {
          sanitized.set(e.getKey(), e.getValue());
        }
      });
      for (String key : MAPPING_KEYS) {
        String field = mappings.path(key).asText(key.equals("timestamp") ? "_time" : key);
        if (raw.has(field)) {
          String v = ActivityLog.redact(raw.path(field).asText());
          mapped.put(key, v);
        }
      }
      if (input.path("maskSensitive").asBoolean(true)) {
        mask(sanitized);
        mask(mapped);
      }
    }
    ObjectNode out = J.objectNode();
    out.put("index", index);
    out.set("fieldMappings", mappings);
    out.set("sample", rows);
    out.put("count", rows.size());
    return out;
  }

  private void mask(ObjectNode node) {
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    for (String k : keys) {
      String v = node.path(k).asText("");
      v = v.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "<email>");
      v = v.replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "<ip>");
      node.put(k, v);
    }
  }

  /** Build WindowStats for one resolved window via a stats SPL over the connection. */
  public WindowStats collectStats(String connectionId, com.apiintel.compare.Window window, String username) {
    var connection = splunkConnection(connectionId, username);
    List<String> indexes = approvedIndexes(connection);
    String index = indexes.isEmpty() ? "main" : String.join(" OR index=", indexes);
    if (indexes.size() > 1) index = "index=" + String.join(" OR index=", indexes);
    else index = "index=" + index;
    ObjectNode mappings = connection.path("fieldMappings").isObject()
        ? (ObjectNode) connection.path("fieldMappings") : J.objectNode();
    String statusField = mappings.path("httpStatus").asText("httpStatus");
    String durationField = mappings.path("durationMs").asText("durationMs");
    String spl = "search " + index
        + " | stats count, sum(eval(if(" + statusField + ">=500,1,0))) as failures,"
        + " sum(eval(if(" + statusField + "<500,1,0))) as successes,"
        + " values(" + durationField + ") as durations, max(_time) as latest";
    try {
      String sid = createJob(connection, spl,
          window.start().getEpochSecond(), window.end().getEpochSecond());
      JobState st = awaitDone(connection, sid, Math.min(120, limit(connection, "maxJobSeconds", 300)));
      if (!"DONE".equalsIgnoreCase(st.status())) return WindowStats.missing("search " + st.status());
      ResultPage page = results(connection, sid, 0, 1, limit(connection, "maxResults", 50000));
      try { cancelSid(connection, sid); } catch (Exception ignored) {}
      if (page.results().isEmpty()) return WindowStats.zero(null);
      ObjectNode row = page.results().get(0);
      long count = row.path("count").asLong(0);
      List<Double> durations = new ArrayList<>();
      JsonNode d = row.path("durations");
      if (d.isArray()) d.forEach(v -> durations.add(v.asDouble()));
      else if (d.isNumber()) durations.add(d.asDouble());
      else if (d.isTextual() && !d.asText().isBlank()) durations.add(d.asDouble());
      return new WindowStats(true, window.partial(), count,
          row.path("successes").asLong(0), row.path("failures").asLong(0),
          durations, Map.of(), Map.of(), Map.of(),
          row.hasNonNull("latest") ? row.path("latest").asLong() : null, null);
    } catch (SplunkClient.SplunkException e) {
      return WindowStats.missing(e.getMessage());
    }
  }

  /** Bounded, redacted representative events for an investigation package. */
  public List<ObjectNode> eventsForInvestigation(String connectionId, ObjectNode inv,
                                               int maxEvents, String username) {
    var connection = splunkConnection(connectionId, username);
    String index = approvedIndexes(connection).stream().findFirst().orElse("main");
    ObjectNode mappings = connection.path("fieldMappings").isObject()
        ? (ObjectNode) connection.path("fieldMappings") : J.objectNode();
    StringBuilder spl = new StringBuilder("search index=" + index);
    String service = inv.path("service").asText("");
    if (!service.isBlank()) {
      spl.append(" ").append(mappings.path("service").asText("service"))
          .append("=\"").append(service.replace("\"", "")).append("\"");
    }
    String cid = inv.path("correlationId").asText("");
    if (!cid.isBlank()) {
      spl.append(" ").append(mappings.path("correlationId").asText("correlationId"))
          .append("=\"").append(cid.replace("\"", "")).append("\"");
    }
    spl.append(" | head ").append(maxEvents);
    var w = inv.path("window");
    long latest = w.path("endEpoch").asLong(Instant.now().getEpochSecond());
    long earliest = w.path("startEpoch").asLong(latest - 3600);
    String sid = createJob(connection, spl.toString(), earliest, latest);
    JobState st = awaitDone(connection, sid, 30);
    if (!"DONE".equalsIgnoreCase(st.status())) {
      try { cancelSid(connection, sid); } catch (Exception ignored) {}
      return List.of();
    }
    ResultPage page = results(connection, sid, 0, maxEvents, limit(connection, "maxResults", 50000));
    try { cancelSid(connection, sid); } catch (Exception ignored) {}
    List<ObjectNode> out = new ArrayList<>();
    for (ObjectNode raw : page.results()) {
      ObjectNode mapped = J.objectNode();
      for (String key : MAPPING_KEYS) {
        String field = mappings.path(key).asText(key.equals("timestamp") ? "_time" : key);
        if (raw.has(field)) mapped.put(key, ActivityLog.redact(raw.path(field).asText()));
      }
      out.add(mapped);
    }
    return out;
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }
}
