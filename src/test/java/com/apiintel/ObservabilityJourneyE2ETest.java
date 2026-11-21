package com.apiintel;

import static org.junit.jupiter.api.Assertions.*;

import com.apiintel.studio.BrowserLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.CookieManager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Observability journey over a file-backed SQLite database with a real
 * Spring restart: alice connects a fixture Splunk, validates, previews a
 * same-time comparison, saves a report + interval schedule, breaches trigger
 * a deduplicated alert (one notification across repeated breaching runs),
 * the app restarts mid-journey without duplicating a planned run, alice
 * investigates via her own AI provider and drafts a Jira incident through
 * the reviewed-write flow. A second journey records an internal tool via a
 * fake browser, infers the connector with evidence, confirms low-confidence
 * fields, validates and uses it in chat + correlation search. Webhook outbox
 * retries keep one Idempotency-Key; revoked credentials notify the owner
 * once; Splunk 503s degrade to MISSING and recover.
 */
class ObservabilityJourneyE2ETest {

  static final String VAULT_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  static final ObjectMapper JSON = new ObjectMapper();
  static FakeLauncher launcher;
  static HttpServer fixture;
  static String fixtureBase;

  static volatile boolean breach;
  static volatile boolean revoked;
  static volatile boolean splunk503;
  static final AtomicInteger sidCounter = new AtomicInteger();
  static final AtomicInteger webhookHits = new AtomicInteger();
  static final List<String> webhookKeys = new CopyOnWriteArrayList<>();
  static final java.util.Map<String, Boolean> sidCurrent = new java.util.concurrent.ConcurrentHashMap<>();

  // ---------------- fixture ----------------

  static class FakeLauncher implements BrowserLauncher {
    final AtomicInteger opens = new AtomicInteger();
    BrowserLauncher.OpenRequest request;

    @Override
    public BrowserHandle open(OpenRequest req) {
      opens.incrementAndGet();
      this.request = req;
      return new BrowserHandle() {
        @Override public String currentUrl() { return fixtureBase + "/app"; }
        @Override public Object evaluate(String script) { return fixtureBase + "/app"; }
        @Override public void installRecorder(String js) { }
        @Override public String storageState() { return "{\"cookies\":[]}"; }
        @Override public String identity() { return "alice@fixture.example"; }
        @Override public void close() { }
      };
    }

    void emitCapture(ObjectNode capture) { request.onCapture().accept(capture); }
  }

  static String statsRow(boolean current) {
    // current window: breaching 10% errors when flagged, otherwise healthy 2%;
    // baselines always healthy so pp change is a real signal.
    if (current && breach) {
      return "{\"count\":\"300\",\"failures\":\"30\",\"successes\":\"270\","
          + "\"durations\":[\"40\",\"42\",\"41\"],\"latest\":\""
          + Instant.now().getEpochSecond() + "\"}";
    }
    return "{\"count\":\"300\",\"failures\":\"6\",\"successes\":\"294\","
        + "\"durations\":[\"40\",\"42\",\"41\"],\"latest\":\""
        + Instant.now().getEpochSecond() + "\"}";
  }

  static void startFixture() throws IOException {
    fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fixture.createContext("/services/server/info", ex -> {
      if (revoked) respond(ex, 401, "{\"messages\":[{\"text\":\"token revoked\"}]}");
      else respond(ex, 200, "{\"entry\":[{\"content\":{\"version\":\"9.1.0\"}}]}");
    });
    fixture.createContext("/services/auth/login", ex -> respond(ex, 200,
        "{\"sessionKey\":\"sess-abc\"}"));
    fixture.createContext("/services/authentication/current-context", ex -> {
      if (revoked) respond(ex, 401, "{\"messages\":[{\"text\":\"session revoked\"}]}");
      else respond(ex, 200, "{\"entry\":[{\"content\":{\"username\":\"svc\","
          + "\"roles\":[\"admin\"],\"srchJobsQuota\":10,\"srchDiskQuota\":250}}]}");
    });
    fixture.createContext("/servicesNS/-/search/saved/searches", ex -> respond(ex, 200,
        "{\"entry\":[{\"name\":\"errors\",\"content\":{\"search\":\"search index=main | stats count\","
            + "\"eai:acl\":{\"app\":\"search\"}}}]}"));
    fixture.createContext("/servicesNS/-/search/search/v2/jobs", ex -> {
      String path = ex.getRequestURI().getPath();
      if (path.endsWith("/control")) { respond(ex, 200, "{}"); return; }
      if ("POST".equals(ex.getRequestMethod()) && path.endsWith("/jobs")) {
        if (splunk503) {
          respond(ex, 503, "{\"messages\":[{\"text\":\"quota exceeded max concurrent searches\"}]}");
          return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sid = "sid-" + sidCounter.incrementAndGet();
        long latest = latestParam(body);
        sidCurrent.put(sid, latest == -1
            || latest >= Instant.now().getEpochSecond() - 200);
        respond(ex, 200, "{\"sid\":\"" + sid + "\"}");
        return;
      }
      // GET status for /jobs/{sid}
      respond(ex, 200, "{\"entry\":[{\"content\":{\"dispatchState\":\"DONE\","
          + "\"doneProgress\":1.0,\"resultCount\":1,\"isFailed\":false}}]}");
    });
    fixture.createContext("/services/search/v2/jobs/", ex -> {
      // /services/search/v2/jobs/{sid}/results
      ex.getRequestBody().readAllBytes();
      String path = ex.getRequestURI().getPath();
      String sid = path.substring("/services/search/v2/jobs/".length())
          .replace("/results", "");
      respond(ex, 200, "{\"results\":[" + statsRow(sidCurrent.getOrDefault(sid, true)) + "]}");
    });
    fixture.createContext("/rest/api/2/myself", ex -> respond(ex, 200,
        "{\"displayName\":\"Alice\",\"accountId\":\"alice-1\"}"));
    fixture.createContext("/rest/api/3/myself", ex -> respond(ex, 200,
        "{\"displayName\":\"Alice\",\"accountId\":\"alice-1\"}"));
    fixture.createContext("/probe", ex -> respond(ex, 200, "{\"ok\":true}"));
    fixture.createContext("/api/items", ex -> respond(ex, 200,
        "{\"items\":[{\"id\":\"req-42\",\"service\":\"checkout\"}],\"nextCursor\":null}"));
    fixture.createContext("/chat", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String reply = body.contains("Connectivity check")
          ? "OK"
          : "Observations: error rate rose sharply [E1]. Possible causes: a bad deploy [E1]. "
            + "Verification steps: compare against the baseline window [E1].";
      respond(ex, 200, "{\"response\":" + JSON.writeValueAsString(reply) + "}");
    });
    fixture.createContext("/hook", ex -> {
      webhookKeys.add(ex.getRequestHeaders().getFirst("Idempotency-Key"));
      ex.getRequestBody().readAllBytes();
      if (webhookHits.incrementAndGet() <= 2) respond(ex, 500, "{\"error\":\"down\"}");
      else respond(ex, 200, "{}");
    });
    fixture.start();
    fixtureBase = "http://127.0.0.1:" + fixture.getAddress().getPort();
  }

  static long latestParam(String form) {
    Matcher m = Pattern.compile("latest_time=(\\d+)").matcher(form);
    return m.find() ? Long.parseLong(m.group(1)) : -1;
  }

  static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
  }

  @AfterAll
  static void stopFixture() { if (fixture != null) fixture.stop(0); }

  // ---------------- http client ----------------

  static class Client {
    final String base;
    final HttpClient http;
    String csrf = "";

    Client(int port) {
      base = "http://127.0.0.1:" + port;
      http = HttpClient.newBuilder().cookieHandler(new CookieManager())
          .connectTimeout(Duration.ofSeconds(10)).build();
    }

    private HttpRequest.Builder req(String path) {
      return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
    }

    void login(String user, String password) throws Exception {
      var r = http.send(req("/api/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
      csrf = JSON.readTree(r.body()).path("token").asText();
      var resp = http.send(req("/api/login")
          .header("Content-Type", "application/x-www-form-urlencoded")
          .header("X-XSRF-TOKEN", csrf)
          .POST(HttpRequest.BodyPublishers.ofString(
              "username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                  + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(204, resp.statusCode(), "login failed for " + user);
      csrf = JSON.readTree(http.send(req("/api/csrf").GET().build(),
          HttpResponse.BodyHandlers.ofString()).body()).path("token").asText();
    }

    HttpResponse<String> raw(String method, String path, String json) throws Exception {
      var builder = req(path).header("X-XSRF-TOKEN", csrf);
      if (json != null) builder.header("Content-Type", "application/json");
      builder.method(method, json == null
          ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(json));
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode call(String method, String path, String json) throws Exception {
      var resp = raw(method, path, json);
      assertTrue(resp.statusCode() < 300,
          method + " " + path + " -> " + resp.statusCode() + " " + resp.body());
      String body = resp.body();
      return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    }
  }

  // ---------------- app lifecycle ----------------

  ConfigurableApplicationContext startApp(Path dbFile, Path profiles) {
    launcher = new FakeLauncher();
    return new SpringApplicationBuilder(PlatformApplication.class)
        .initializers((ApplicationContextInitializer<GenericApplicationContext>) ctx -> {
          ctx.registerBean("e2eLauncher", BrowserLauncher.class, () -> launcher,
              def -> def.setPrimary(true));
          ctx.registerBean("e2eGuard", com.apiintel.network.NetworkGuard.class,
              () -> new com.apiintel.network.NetworkGuard() {
                @Override public void check(java.net.InetAddress address) {
                  if (address.isLoopbackAddress()) return;
                  super.check(address);
                }
              },
              def -> def.setPrimary(true));
        })
        .run(
            "--server.port=0",
            "--spring.datasource.url=jdbc:sqlite:" + dbFile,
            "--platform.vault-key=" + VAULT_KEY,
            "--platform.admin-password=admin-password-1",
            "--platform.seed=false",
            "--platform.browser-profiles=" + profiles,
            "--logging.level.root=WARN");
  }

  int port(ConfigurableApplicationContext ctx) {
    return Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
  }

  /** Poll until the supplier returns a satisfying value or the deadline passes. */
  static <T> T await(String what, java.util.concurrent.Callable<T> probe,
      java.util.function.Predicate<T> ok, long seconds) throws Exception {
    long deadline = System.currentTimeMillis() + seconds * 1000;
    T last = null;
    while (System.currentTimeMillis() < deadline) {
      last = probe.call();
      if (ok.test(last)) return last;
      Thread.sleep(1000);
    }
    fail("timed out waiting for " + what + " (last=" + last + ")");
    return last;
  }

  // ---------------- journey ----------------

  @Test
  void observabilityJourneySurvivesRestart() throws Exception {
    startFixture();
    Path dir = Files.createTempDirectory("e2e-observability");
    Path dbFile = dir.resolve("platform.sqlite");
    Path profiles = dir.resolve("profiles");

    String reportId, scheduleId, splunkId, customId, invId;
    int runsAtRestart;

    var app = startApp(dbFile, profiles);
    try {
      int p = port(app);
      var admin = new Client(p);
      admin.login("admin", "admin-password-1");
      admin.call("POST", "/api/users",
          "{\"username\":\"alice\",\"password\":\"alice-password-1\",\"displayName\":\"Alice\",\"roles\":[\"DEVELOPER\"]}");
      admin.call("POST", "/api/users",
          "{\"username\":\"bob\",\"password\":\"bob-password-12\",\"displayName\":\"Bob\",\"roles\":[\"DEVELOPER\"]}");
      var alice = new Client(p);
      alice.login("alice", "alice-password-1");
      var bob = new Client(p);
      bob.login("bob", "bob-password-12");

      // -- journey 1: splunk connect -> validate -> preview -> report + schedule --
      var connInput = JSON.createObjectNode()
          .put("provider", "splunk").put("name", "prod-splunk")
          .put("baseUrl", fixtureBase).put("authType", "SPLUNK_TOKEN")
          .put("credential", "tok-1").put("deployment", "ENTERPRISE")
          .put("defaultTimezone", "UTC").put("splunkApp", "search");
      connInput.putArray("approvedIndexes").add("main");
      var fm = connInput.putObject("fieldMappings");
      fm.put("timestamp", "_time").put("service", "svc_name")
          .put("httpStatus", "status").put("durationMs", "dur")
          .put("correlationId", "correlationId").put("exception", "exception");
      var limits = connInput.putObject("limits");
      limits.put("maxConcurrentJobs", 2).put("maxResults", 1000).put("pageSize", 500);
      var conn = alice.call("POST", "/api/connections", connInput.toString());
      splunkId = conn.path("id").asText();

      var validation = alice.call("POST", "/api/splunk/" + splunkId + "/validate", "{}");
      assertTrue(validation.path("authenticate").path("ok").asBoolean(), validation.toString());
      assertTrue(validation.path("search").path("ok").asBoolean());
      assertTrue(validation.path("retrieve").path("ok").asBoolean());
      assertEquals("v2", validation.path("apiVersion").asText());
      assertEquals(10, validation.path("quotas").path("srchJobsQuota").asInt());

      var reportInput = JSON.createObjectNode().put("name", "service health");
      reportInput.putArray("connectionIds").add(splunkId);
      reportInput.putArray("metrics").add("requests").add("error_rate");
      var policy = reportInput.putObject("policy");
      policy.put("preset", "LAST_5M").put("mode", "ROLLING").put("zone", "UTC")
          .put("bucketSeconds", 60).put("lagSeconds", 0).put("completedBucketsOnly", false);

      var preview = alice.call("POST", "/api/reports/preview", reportInput.toString());
      assertTrue(preview.path("metrics").size() > 0, preview.toString());
      assertTrue(preview.has("resolvedWindows"));

      var saved = alice.call("POST", "/api/reports", reportInput.toString());
      reportId = saved.path("id").asText();

      // webhook destination: save -> preview -> enable (host approved on enable)
      var dest = alice.call("POST", "/api/notify/destinations",
          "{\"type\":\"WEBHOOK\",\"name\":\"oncall-hook\",\"url\":\"" + fixtureBase + "/hook\"}");
      String destId = dest.path("id").asText();
      assertTrue(dest.path("hasUrl").asBoolean());
      assertFalse(dest.path("enabled").asBoolean());
      alice.call("POST", "/api/notify/destinations/" + destId + "/preview", "{}");
      alice.call("POST", "/api/notify/destinations/" + destId + "/enable", "{\"confirm\":true}");

      // alert rule: error_rate > 5% AND requests >= 200 AND pp change >= 2pp
      var rule = JSON.createObjectNode()
          .put("name", "error spike").put("reportId", reportId)
          .put("metric", "error_rate").put("groupBy", "none").put("severity", "HIGH")
          .put("consecutive", 1).put("notifyRecovery", true);
      var conds = rule.putArray("conditions");
      conds.addObject().put("type", "ABSOLUTE").put("metric", "error_rate").put("op", ">").put("value", 0.05);
      conds.addObject().put("type", "ABSOLUTE").put("metric", "requests").put("op", ">=").put("value", 200);
      conds.addObject().put("type", "RATE_CHANGE").put("metric", "error_rate").put("op", ">=").put("value", 0.02);
      rule.putArray("destinations").add(destId);
      alice.call("POST", "/api/alerts/rules", rule.toString());

      // interval schedule every 4s, skipped-missed policy, quick retries
      var sched = alice.call("POST", "/api/schedules", "{"
          + "\"reportId\":\"" + reportId + "\",\"kind\":\"INTERVAL\",\"expr\":\"4\","
          + "\"zone\":\"UTC\",\"missedPolicy\":\"SKIP\",\"retryLimit\":3,"
          + "\"backoffBaseS\":1,\"deadlineS\":60}");
      scheduleId = sched.path("id").asText();

      // flip fixture to breaching; wait for >= 3 successful runs and FIRING incident
      breach = true;
      await("3 successful runs",
          () -> alice.call("GET", "/api/schedules/" + scheduleId + "/runs", null),
          runs -> countStatus(runs, "SUCCEEDED") >= 3, 120);
      var incidents = await("FIRING incident",
          () -> alice.call("GET", "/api/alerts/incidents", null),
          list -> list.size() > 0 && "FIRING".equals(list.get(0).path("state").asText()), 60);

      // exactly ONE deduplicated outbox notification across all breaching runs
      var outboxRows = await("one deduplicated alert notification",
          () -> alice.call("GET", "/api/notify/outbox", null),
          rows -> {
            int n = 0;
            for (JsonNode r : rows) {
              if (destId.equals(r.path("destinationId").asText(r.path("destination_id").asText()))) n++;
            }
            return n >= 1;
          }, 60);
      int episodeRows = 0;
      for (JsonNode r : outboxRows) {
        if (destId.equals(r.path("destinationId").asText(r.path("destination_id").asText()))) episodeRows++;
      }
      assertEquals(1, episodeRows,
          "expected a single deduplicated alert notification: " + outboxRows);

      // webhook outbox: 2 failures then SENT with the same Idempotency-Key
      await("webhook outbox SENT",
          () -> alice.call("GET", "/api/notify/outbox", null),
          rows -> {
            for (JsonNode r : rows) {
              if (destId.equals(r.path("destinationId").asText(r.path("destination_id").asText()))
                  && "SENT".equals(r.path("status").asText())) return true;
            }
            return false;
          }, 90);
      assertTrue(webhookHits.get() >= 3, "expected >=3 webhook deliveries, got " + webhookHits.get());
      assertTrue(webhookKeys.size() >= 3 && webhookKeys.stream().distinct().count() == 1,
          "Idempotency-Key must be stable across retries: " + webhookKeys);

      // revoked permissions at schedule run -> owner notified once
      revoked = true;
      await("a failed run after revocation",
          () -> alice.call("GET", "/api/schedules/" + scheduleId + "/runs", null),
          runs -> countStatus(runs, "FAILED") >= 1 || countStatus(runs, "DEAD") >= 1, 60);
      await("credential notification",
          () -> alice.call("GET", "/api/notifications", null),
          list -> list.toString().contains("credentials expired"), 60);
      Thread.sleep(8000); // allow a second failed attempt
      var notesAfterRevoke = alice.call("GET", "/api/notifications", null);
      assertEquals(1, countContaining(notesAfterRevoke, "credentials expired"),
          "revoked access must notify the owner exactly once: " + notesAfterRevoke);
      revoked = false;

      // Splunk 503 -> snapshots degrade to MISSING; recover afterwards
      splunk503 = true;
      await("MISSING snapshot during outage",
          () -> alice.call("GET", "/api/reports/" + reportId + "/snapshots", null),
          snaps -> containsField(snaps, "completeness", "MISSING"), 90);
      splunk503 = false;
      await("snapshot recovers after outage",
          () -> alice.call("GET", "/api/reports/" + reportId + "/snapshots", null),
          snaps -> {
            if (snaps.size() == 0) return false;
            // newest first — assert the latest snapshot is measured again
            return "COMPLETE".equals(snaps.get(0).path("completeness").asText())
                || "PARTIAL".equals(snaps.get(0).path("completeness").asText());
          }, 90);

      // investigate latest snapshot with alice's own AI provider (journey-2 conn below)
      var custom = alice.call("POST", "/api/connections", customBody());
      customId = custom.path("id").asText();
      assertEquals("CONNECTED", custom.path("state").asText(), custom.toString());

      var snaps = alice.call("GET", "/api/reports/" + reportId + "/snapshots", null);
      String snapId = snaps.get(0).path("id").asText();
      var inv = alice.call("POST", "/api/investigations",
          "{\"snapshotId\":\"" + snapId + "\",\"connectionId\":\"" + splunkId + "\"}");
      invId = inv.path("id").asText();
      assertTrue(inv.path("evidence").size() > 0, inv.toString());

      var invPreview = alice.call("POST", "/api/investigations/" + invId + "/preview",
          "{\"connectionId\":\"" + customId + "\"}");
      var sse = alice.raw("POST", "/api/investigations/" + invId + "/send",
          "{\"connectionId\":\"" + customId + "\",\"confirmationId\":\""
              + invPreview.path("confirmationId").asText() + "\"}");
      assertEquals(200, sse.statusCode());
      assertTrue(sse.body().contains("E1"), sse.body());

      // Jira incident draft goes through the reviewed-write proposal
      var jiraConn = alice.call("POST", "/api/connections",
          "{\"provider\":\"jira\",\"name\":\"jira-alice\",\"baseUrl\":\"" + fixtureBase
              + "\",\"authType\":\"BASIC\",\"credential\":\"alice@x.com:tok\"}");
      var draft = alice.call("POST", "/api/investigations/" + invId + "/jira-draft", "{}");
      assertEquals("PENDING", draft.path("status").asText(), draft.toString());
      assertEquals(jiraConn.path("id").asText(), draft.path("connectionId").asText());

      // resolve -> knowledge searchable
      alice.call("POST", "/api/investigations/" + invId + "/resolve",
          "{\"resolution\":\"reverted bad deploy of checkout\"}");
      var knowledge = alice.call("GET",
          "/api/knowledge/search?q=" + URLEncoder.encode("reverted", StandardCharsets.UTF_8), null);
      assertTrue(knowledge.size() > 0, "resolution should be searchable: " + knowledge);

      // -- journey 2: record internal tool via fake browser -> infer -> validate --
      var session = alice.call("POST", "/api/studio/sessions",
          "{\"connectionId\":\"" + customId + "\",\"url\":\"" + fixtureBase + "\"}");
      String studioSession = session.path("id").asText();
      for (int i = 0; i < 40 && launcher.request == null; i++) Thread.sleep(100);
      assertNotNull(launcher.request, "studio browser never opened");
      for (int i = 0; i < 40; i++) {
        var state = alice.call("GET", "/api/studio/sessions/" + studioSession, null);
        if ("WAITING_FOR_SIGN_IN".equals(state.path("status").asText())) break;
        Thread.sleep(100);
      }
      alice.call("POST", "/api/studio/sessions/" + studioSession + "/signed-in", null);
      alice.call("POST", "/api/studio/sessions/" + studioSession + "/record/start",
          "{\"promptMarker\":\"journey-probe\"}");
      var listCapture = JSON.createObjectNode();
      listCapture.put("method", "GET").put("url", fixtureBase + "/api/items?cursor=0")
          .put("status", 200).put("contentType", "application/json")
          .put("responsePreview", "{\"items\":[],\"nextCursor\":\"1\"}");
      launcher.emitCapture(listCapture);
      var chatCapture = JSON.createObjectNode();
      chatCapture.put("method", "POST").put("url", fixtureBase + "/chat")
          .put("status", 200).put("contentType", "application/json")
          .put("body", "{\"prompt\":\"journey-probe\"}")
          .put("responsePreview", "{\"response\":\"fixture reply\"}");
      launcher.emitCapture(chatCapture);
      alice.call("POST", "/api/studio/sessions/" + studioSession + "/record/stop", null);

      var inferred = alice.call("POST", "/api/studio/sessions/" + studioSession + "/infer",
          "{\"promptMarker\":\"journey-probe\"}");
      assertTrue(inferred.has("evidence"), "inference must carry evidence: " + inferred);

      // confirm every LOW-confidence field; authorize the recorded POST step
      var confirmed = JSON.createArrayNode();
      inferred.path("evidence").fields().forEachRemaining(f -> {
        if ("LOW".equals(f.getValue().path("confidence").asText())) confirmed.add(f.getKey());
      });
      var authorized = JSON.createArrayNode();
      for (JsonNode step : inferred.path("steps")) {
        String m = step.path("method").asText("GET");
        if (!"GET".equalsIgnoreCase(m)) {
          authorized.addObject().put("method", m).put("path", step.path("path").asText());
        }
      }
      var validateBody = JSON.createObjectNode();
      validateBody.set("config", inferred);
      validateBody.set("confirmedFields", confirmed);
      validateBody.set("authorizedOperations", authorized);
      var validated = alice.call("POST", "/api/studio/sessions/" + studioSession + "/validate",
          validateBody.toString());
      assertEquals("CONNECTED", validated.path("state").asText(), validated.toString());
      assertTrue(validated.path("connectorValidated").asBoolean());

      // connector usable in chat and correlation search
      var chatConv = alice.call("POST", "/api/chat/conversations",
          "{\"connectionId\":\"" + customId + "\",\"title\":\"ops\"}");
      var chatPreview = alice.call("POST",
          "/api/chat/conversations/" + chatConv.path("id").asText() + "/preview",
          "{\"prompt\":\"status?\",\"connectionId\":\"" + customId + "\"}");
      var chatSse = alice.raw("POST",
          "/api/chat/conversations/" + chatConv.path("id").asText() + "/send",
          "{\"prompt\":\"status?\",\"connectionId\":\"" + customId
              + "\",\"confirmationId\":\"" + chatPreview.path("confirmationId").asText() + "\"}");
      assertEquals(200, chatSse.statusCode());
      assertTrue(chatSse.body().contains("event:done"), chatSse.body());

      var corr = alice.call("GET", "/api/connectors/correlation/req-42", null);
      assertTrue(corr.path("sources").toString().contains(customId)
          || corr.path("sources").size() > 0, corr.toString());

      // -- bob sees none of alice's data --
      assertEquals(0, ((JsonNode) bob.call("GET", "/api/reports", null)).size());
      assertEquals(0, ((JsonNode) bob.call("GET", "/api/alerts/incidents", null)).size());
      assertEquals(0, ((JsonNode) bob.call("GET", "/api/notifications", null)).size());
      assertEquals(0, ((JsonNode) bob.call("GET",
          "/api/knowledge/search?q=reverted", null)).size());
      int bobSnap = bob.raw("GET", "/api/reports/snapshots/" + snapId, null).statusCode();
      assertTrue(bobSnap == 403 || bobSnap == 404, "bob read alice's snapshot: " + bobSnap);
      int bobInv = bob.raw("GET", "/api/investigations/" + invId, null).statusCode();
      assertTrue(bobInv == 403 || bobInv == 404);

      runsAtRestart = alice.call("GET", "/api/schedules/" + scheduleId + "/runs", null).size();
    } finally {
      app.close();
    }

    // -- restart on the same SQLite file --
    var app2 = startApp(dbFile, profiles);
    try {
      var alice2 = new Client(port(app2));
      alice2.login("alice", "alice-password-1");

      // everything persisted
      var incidents2 = alice2.call("GET", "/api/alerts/incidents", null);
      // the outage resolved the first episode and breaching resumed into a
      // new one — the point is persistence, not a single incident
      assertTrue(incidents2.size() >= 1, "incidents must survive restart");
      assertTrue(incidents2.toString().contains("FIRING"));
      var snaps2 = alice2.call("GET", "/api/reports/" + reportId + "/snapshots", null);
      assertTrue(snaps2.size() > 0);
      var inv2 = alice2.call("GET", "/api/investigations/" + invId, null);
      assertEquals(invId, inv2.path("id").asText());

      // schedule kept running; no duplicate run for any planned time
      var runs = await("a post-restart run",
          () -> alice2.call("GET", "/api/schedules/" + scheduleId + "/runs", null),
          r -> r.size() > runsAtRestart, 90);
      var seen = new java.util.HashSet<String>();
      for (JsonNode run : runs) {
        String planned = run.path("plannedAt").asText(run.path("planned_at").asText());
        assertTrue(seen.add(planned), "duplicate run for planned time " + planned);
      }
    } finally {
      app2.close();
    }
  }

  static String customBody() {
    var node = JSON.createObjectNode()
        .put("provider", "custom").put("name", "Internal Tool")
        .put("baseUrl", fixtureBase).put("authType", "BEARER")
        .put("credential", "internal-token")
        .put("aiChatEnabled", true).put("chatPath", "/chat")
        .put("aiProbePath", "/probe").put("aiResponsePointer", "/response")
        .put("aiResponseFormat", "json")
        .put("correlationPath", "/api/items");
    node.putObject("aiRequestTemplate").put("prompt", "{{prompt}}");
    return node.toString();
  }

  static int countStatus(JsonNode runs, String status) {
    int n = 0;
    for (JsonNode r : runs) {
      if (status.equals(r.path("status").asText())) n++;
    }
    return n;
  }

  static int countContaining(JsonNode notes, String text) {
    int n = 0;
    for (JsonNode node : notes) if (node.toString().contains(text)) n++;
    return n;
  }

  static boolean containsField(JsonNode array, String field, String value) {
    for (JsonNode n : array) {
      if (value.equals(n.path(field).asText())) return true;
    }
    return false;
  }
}
