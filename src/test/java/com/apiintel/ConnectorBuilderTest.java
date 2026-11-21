package com.apiintel;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.apiintel.activity.ActivityLog;
import com.apiintel.connectors.*;
import com.apiintel.integrations.*;
import com.apiintel.jira.JiraService;
import com.apiintel.network.NetworkGuard;
import com.apiintel.security.PlatformUserService;
import com.apiintel.security.Vault;
import com.apiintel.splunk.SplunkClient;
import com.apiintel.splunk.SplunkService;
import com.apiintel.store.RecordStore;
import com.apiintel.studio.ConnectorInference;
import com.apiintel.studio.HarImport;
import com.apiintel.studio.StudioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Connector builder backend coverage: sanitized HAR import, inference
 * evidence/confidence and validate-time safeguards, schema drift, codegen,
 * GitHub PR through the reviewed-write path, connector versions, cost limits.
 */
class ConnectorBuilderTest {

  static final JsonNodeFactory J = JsonNodeFactory.instance;
  static final ObjectMapper JSON = new ObjectMapper();

  WireMockServer server;
  JdbcTemplate db;
  RecordStore store;
  Vault vault;
  PlatformUserService users;
  IntegrationService integrations;
  ConnectionService connections;
  ApprovedHosts approved;
  ActivityLog activity;
  JiraService jira;
  GithubService github;
  DriftMonitor drift;
  ConnectorService connectors;
  SplunkService splunk;
  StudioService studio;

  @BeforeEach
  void setup() {
    server = new WireMockServer(0);
    server.start();
    String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    store = new RecordStore(db, JSON);
    vault = new Vault(db, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    users = new PlatformUserService(db,
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());
    users.bootstrap("admin-password-1", "developer-password-1", "reviewer-password-1");
    users.create("alice", "alice-password-1", "Alice", List.of("DEVELOPER"), true);
    users.create("bob", "bob-password-123", "Bob", List.of("DEVELOPER"), true);
    var guard = org.mockito.Mockito.mock(NetworkGuard.class);
    org.mockito.Mockito.when(guard.validate(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(a -> java.net.URI.create(a.getArgument(0)));
    approved = new ApprovedHosts(store, guard, "", "", "http://localhost:18080");
    integrations = new IntegrationService(store, vault, guard, approved, "http://localhost:18080");
    connections = new ConnectionService(store, vault, users, integrations, approved);
    activity = new ActivityLog(db);
    jira = new JiraService(store, connections, integrations,
        new com.apiintel.oauth.TokenRefresher(store, vault, approved));
    github = new GithubService(store, connections, integrations);
    integrations.setPersonalExecutors(List.of(jira, github));
    drift = new DriftMonitor(store, activity);
    org.springframework.test.util.ReflectionTestUtils.setField(
        integrations, "driftMonitor", drift);
    splunk = new SplunkService(store, connections, users, vault,
        new SplunkClient(vault, approved));
    var notify = new com.apiintel.notify.NotifyService(
        db, store, vault, users, approved, null, "");
    var alerts = new com.apiintel.alerts.AlertService(store, db, users, notify);
    var reports = new com.apiintel.reports.ReportService(
        store, connections, users, splunk, db, List.of(alerts));
    connectors = new ConnectorService(store, connections, users, integrations, db, github);
    connectors.wire(reports, alerts, null, jira, splunk);
    studio = new StudioService(store, vault, connections, integrations, approved, activity,
        "./target/test-profiles", null);
  }

  @AfterEach
  void cleanup() {
    server.stop();
  }

  ObjectNode connection(String provider, String username) {
    var input = J.objectNode()
        .put("provider", provider).put("name", provider + "-" + username)
        .put("baseUrl", server.baseUrl()).put("authType", "BASIC")
        .put("credential", "user:pass");
    if ("github".equals(provider) || "jira".equals(provider)) {
      server.stubFor(get(urlPathMatching("/rest/api/[23]/myself"))
          .willReturn(okJson("{\"displayName\":\"u\"}")));
      if ("github".equals(provider)) {
        server.stubFor(get(urlPathEqualTo("/user")).willReturn(okJson("{\"login\":\"u\"}")));
      }
    }
    return connections.connect(input, username);
  }

  // ---------- HAR import ----------

  @Test
  void harImportStripsSecretsAndStaticAssets() {
    String har = "{\"log\":{\"entries\":["
        + "{\"_resourceType\":\"xhr\",\"request\":{\"method\":\"POST\","
        + "\"url\":\"https://app.example.com/api/chat\","
        + "\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer tok\"},"
        + "{\"name\":\"Cookie\",\"value\":\"s=1\"},"
        + "{\"name\":\"X-API-Key\",\"value\":\"k\"},"
        + "{\"name\":\"Content-Type\",\"value\":\"application/json\"}],"
        + "\"postData\":{\"text\":\"{\\\"prompt\\\":\\\"hi\\\",\\\"sessionToken\\\":\\\"t\\\",\\\"nested\\\":{\\\"apiKey\\\":\\\"x\\\"}}\"}},"
        + "\"response\":{\"status\":200,\"content\":{\"mimeType\":\"application/json\",\"text\":\"{\\\"text\\\":\\\"ok\\\"}\"}}},"
        + "{\"_resourceType\":\"document\",\"request\":{\"method\":\"GET\",\"url\":\"https://app.example.com/\",\"headers\":[]},\"response\":{\"status\":200,\"content\":{\"text\":\"\"}}},"
        + "{\"request\":{\"method\":\"GET\",\"url\":\"https://app.example.com/app.js\",\"headers\":[]},\"response\":{\"status\":200,\"content\":{\"text\":\"\"}}}"
        + "]}}";
    var out = studio.importHar(har.getBytes(java.nio.charset.StandardCharsets.UTF_8), "alice");
    assertEquals(1, out.path("kept").asInt());
    var cap = out.path("captures").get(0);
    assertEquals("POST", cap.path("method").asText());
    assertFalse(cap.path("headers").has("authorization"));
    assertFalse(cap.path("headers").has("cookie"));
    assertFalse(cap.path("headers").has("x-api-key"));
    assertEquals("application/json", cap.path("headers").path("content-type").asText());
    assertFalse(cap.path("body").asText().contains("sessionToken"));
    assertFalse(cap.path("body").asText().contains("apiKey"));
    var removed = out.path("removed");
    assertEquals(2, removed.path("entries").asInt());
    assertTrue(removed.path("headers").toString().contains("authorization"));
    assertTrue(removed.path("headers").toString().contains("cookie"));
    assertTrue(removed.path("headers").toString().contains("x-api-key"));
    assertTrue(removed.path("bodyFields").toString().contains("sessionToken"));
    // nothing secret survives anywhere in the capture
    assertFalse(cap.toString().contains("tok\""));
    assertFalse(cap.toString().contains("s=1"));
  }

  @Test
  void harImportRejectsOversizedAndInvalid() {
    assertThrows(IllegalArgumentException.class, () -> HarImport.sanitize(new byte[0]));
    assertThrows(IllegalArgumentException.class,
        () -> HarImport.sanitize("not json".getBytes()));
    assertThrows(IllegalArgumentException.class,
        () -> HarImport.sanitize("{\"log\":{}}".getBytes()));
  }

  // ---------- inference evidence + validate safeguards ----------

  List<ObjectNode> captures() {
    var c0 = J.objectNode().put("id", "cap-0").put("method", "POST")
        .put("url", server.baseUrl() + "/api/conversations")
        .put("body", "{\"title\":\"chat\"}")
        .put("status", 201)
        .put("responsePreview", "{\"conversationId\":\"conv-7\"}");
    var c1 = J.objectNode().put("id", "cap-1").put("method", "POST")
        .put("url", server.baseUrl() + "/api/messages?cursor=0")
        .put("body", "{\"conversationId\":\"conv-7\",\"text\":\"contract check prompt\"}")
        .put("status", 200).put("contentType", "application/json")
        .put("responsePreview", "{\"choices\":[{\"message\":{\"content\":\"answer text\"}}]}");
    c1.set("headers", J.objectNode().put("x-csrf-token", "c").put("content-type", "application/json"));
    return List.of(c0, c1);
  }

  @Test
  void inferenceProducesStepsEvidenceAndConfidence() {
    var inferred = ConnectorInference.infer(captures(), "contract check prompt");
    assertEquals("/api/messages", inferred.path("chatPath").asText());
    var template = inferred.path("aiRequestTemplate");
    assertEquals("{{prompt}}", template.path("text").asText());
    assertEquals("{{conversationId}}", template.path("conversationId").asText());
    assertEquals("json", inferred.path("aiResponseFormat").asText());
    assertEquals("/choices/0/message/content", inferred.path("aiResponsePointer").asText());
    assertTrue(inferred.path("csrfHeaders").toString().contains("x-csrf-token"));
    var evidence = inferred.path("evidence");
    assertEquals("HIGH", evidence.path("chatPath").path("confidence").asText());
    assertEquals("cap-1", evidence.path("chatPath").path("captureIds").get(0).asText());
    assertEquals("HIGH", evidence.path("requestTemplate").path("confidence").asText());
    assertEquals("HIGH", evidence.path("conversationId").path("confidence").asText());
    assertTrue(evidence.has("pagination") && evidence.has("responseSchema"));
  }

  @Test
  void inferenceConversationIdFromPrecedingResponse() {
    var list = List.of(
        J.objectNode().put("id", "cap-0").put("method", "POST")
            .put("url", server.baseUrl() + "/api/conversations")
            .put("body", "{}")
            .put("responsePreview", "{\"threadId\":\"t-1\"}"),
        J.objectNode().put("id", "cap-1").put("method", "POST")
            .put("url", server.baseUrl() + "/api/messages")
            .put("body", "{\"message\":\"contract check prompt\"}")
            .put("responsePreview", "{\"answer\":\"ok\"}"));
    var inferred = ConnectorInference.infer(
        list.stream().map(n -> (ObjectNode) n).toList(), "contract check prompt");
    var steps = inferred.path("steps");
    assertEquals(2, steps.size());
    assertEquals("/api/conversations", steps.get(0).path("path").asText());
    assertEquals("/threadId", steps.get(0).path("capture").path("conversationId").asText());
    assertEquals("/api/messages", steps.get(1).path("path").asText());
    assertEquals("MEDIUM", inferred.path("evidence").path("conversationId").path("confidence").asText());
  }

  @Test
  void inferenceSseAndSignedParams() {
    var cap = J.objectNode().put("id", "cap-1").put("method", "POST")
        .put("url", server.baseUrl() + "/api/chat?sig=abc&nonce=n")
        .put("body", "{\"q\":\"contract check prompt\"}")
        .put("contentType", "text/event-stream")
        .put("responsePreview", "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n");
    var inferred = ConnectorInference.infer(List.of(cap), "contract check prompt");
    assertEquals("sse", inferred.path("aiResponseFormat").asText());
    assertEquals("/choices/0/delta/content", inferred.path("aiResponsePointer").asText());
    assertEquals("browser", inferred.path("replayMode").asText());
    assertTrue(inferred.path("signedParams").toString().contains("sig"));
  }

  @Test
  void validateRequiresLowConfidenceConfirmationAndWriteAuthorization() {
    var conn = connection("custom", "alice");
    conn.put("baseUrl", server.baseUrl());
    conn = store.save("connections", conn, "alice");
    var session = J.objectNode()
        .put("owner", "alice").put("ownerId", conn.path("ownerId").asText())
        .put("connectionId", conn.path("id").asText()).put("studio", true)
        .put("url", server.baseUrl()).put("status", "REVIEW");
    session.putArray("captures");
    session.putArray("actions");
    session = store.save("discovery-sessions", session, "alice");

    // Inferred config with a LOW field and a non-GET step.
    var inferred = J.objectNode()
        .put("chatPath", "/api/messages").put("aiMethod", "POST")
        .put("aiResponseFormat", "json").put("aiResponsePointer", "/text");
    inferred.putObject("evidence").putObject("responsePointer").put("confidence", "LOW");
    var steps = inferred.putArray("steps");
    steps.addObject().put("method", "POST").put("path", "/api/messages");
    session.set("inferred", inferred);
    store.save("discovery-sessions", session, "alice");
    final String sessionId = session.path("id").asText();

    var input = J.objectNode();
    var ex = assertThrows(IllegalArgumentException.class,
        () -> studio.validate(sessionId, input, "alice"));
    assertTrue(ex.getMessage().contains("low-confidence"), ex.getMessage());

    input.putArray("confirmedFields").add("responsePointer");
    ex = assertThrows(IllegalArgumentException.class,
        () -> studio.validate(sessionId, input, "alice"));
    assertTrue(ex.getMessage().contains("POST /api/messages"), ex.getMessage());
  }

  // ---------- drift ----------

  @Test
  void driftDetectedOnSchemaChange() {
    var conn = connection("custom", "alice");
    conn.put("ownerId", conn.path("ownerId").asText());
    conn.put("schemaHash", DriftMonitor.shapeHash(store.parse("{\"answer\":\"x\"}")));
    conn = store.save("connections", conn, "alice");
    drift.onSuccess(conn, JSON.createObjectNode().put("different", "shape"));
    var after = store.get("connections", conn.path("id").asText());
    assertTrue(after.path("driftDetected").asBoolean());
    assertEquals("DEGRADED", after.path("health").asText());
    assertEquals(1, after.path("executions").asInt());
    assertEquals(0, after.path("failures").asInt());
  }

  @Test
  void noDriftWhenShapeMatches() {
    var conn = connection("custom", "alice");
    JsonNode sample = JSON.createObjectNode().put("answer", "x").put("count", 1);
    conn.put("schemaHash", DriftMonitor.shapeHash(sample));
    conn = store.save("connections", conn, "alice");
    drift.onSuccess(conn, JSON.createObjectNode().put("answer", "y").put("count", 2));
    var after = store.get("connections", conn.path("id").asText());
    assertFalse(after.path("driftDetected").asBoolean(false));
    assertEquals(1, after.path("executions").asInt());
    drift.onFailure(after);
    after = store.get("connections", conn.path("id").asText());
    assertEquals(1, after.path("failures").asInt());
  }

  // ---------- codegen ----------

  ObjectNode connectorConfig() {
    var config = J.objectNode()
        .put("provider", "custom").put("chatPath", "/api/chat")
        .put("aiMethod", "POST").put("aiResponsePointer", "/text")
        .put("aiResponseFormat", "json");
    config.set("aiRequestTemplate", J.objectNode().put("prompt", "{{prompt}}"));
    return config;
  }

  @Test
  void codegenProducesReviewableArtifacts() {
    var conn = connection("custom", "alice");
    var in1 = J.objectNode().put("name", "acme-chat");
    in1.set("config", connectorConfig());
    in1.set("fixture", J.objectNode().put("response", "{\"text\":\"fixture answer\"}"));
    var out = connectors.codegen(conn.path("id").asText(), in1, "alice");
    var files = out.path("files");
    assertEquals(4, files.size());
    String adapter = files.get(2).path("content").asText();
    assertTrue(adapter.contains("class AcmechatAdapter"));
    assertTrue(adapter.contains("implements ChatAdapter"));
    String contract = files.get(3).path("content").asText();
    assertTrue(contract.contains("@Test"));
    assertTrue(out.path("note").asText().contains("never loaded"));
  }

  @Test
  void generatedValidationRunsFixtureContract() {
    var conn = connection("custom", "alice");
    var inOk = J.objectNode();
    inOk.set("config", connectorConfig());
    inOk.set("fixture", store.parse("{\"response\":{\"text\":\"fixture answer\"}}"));
    var ok = connectors.validateGenerated(conn.path("id").asText(), inOk, "alice");
    assertTrue(ok.path("ok").asBoolean());
    assertEquals("fixture answer", ok.path("extracted").asText());
    var inBad = J.objectNode();
    inBad.set("config", connectorConfig());
    inBad.set("fixture", store.parse("{\"response\":{}}"));
    var bad = connectors.validateGenerated(conn.path("id").asText(), inBad, "alice");
    assertFalse(bad.path("ok").asBoolean());
  }

  // ---------- github PR via reviewed write ----------

  @Test
  void connectorPrGoesThroughProposalApproveExecute() {
    var gh = connection("github", "alice");
    var files = J.arrayNode();
    files.addObject().put("path", "src/Acme.java").put("content", "class Acme {}");
    var input = J.objectNode()
        .put("githubConnectionId", gh.path("id").asText())
        .put("repo", "acme/platform").put("base", "main")
        .put("title", "Add acme connector").put("name", "acme");
    input.set("files", files);
    var conn = connection("custom", "alice");
    var proposal = connectors.proposePr(conn.path("id").asText(), input, "alice");
    assertEquals("PENDING", proposal.path("status").asText());
    assertEquals(gh.path("id").asText(), proposal.path("connectionId").asText());
    assertEquals(GithubService.PR_ACTION_PATH, proposal.path("action").path("path").asText());
    assertTrue(proposal.path("action").path("body").path("branch").asText()
        .startsWith("connector/acme-"));

    // owner isolation: bob cannot approve/execute alice's proposal
    var pid = proposal.path("id").asText();
    integrations.approve(pid, "alice");

    server.stubFor(get(urlPathEqualTo("/repos/acme/platform/git/ref/heads/main"))
        .willReturn(okJson("{\"object\":{\"sha\":\"base-sha\"}}")));
    server.stubFor(post(urlPathEqualTo("/repos/acme/platform/git/blobs"))
        .willReturn(okJson("{\"sha\":\"blob-sha\"}")));
    server.stubFor(post(urlPathEqualTo("/repos/acme/platform/git/trees"))
        .willReturn(okJson("{\"sha\":\"tree-sha\"}")));
    server.stubFor(post(urlPathEqualTo("/repos/acme/platform/git/commits"))
        .willReturn(okJson("{\"sha\":\"commit-sha\"}")));
    server.stubFor(post(urlPathEqualTo("/repos/acme/platform/git/refs"))
        .willReturn(okJson("{\"ref\":\"refs/heads/x\"}")));
    server.stubFor(post(urlPathEqualTo("/repos/acme/platform/pulls"))
        .willReturn(okJson("{\"html_url\":\"https://github.test/pr/1\"}")));

    var result = integrations.execute(pid, "alice");
    assertEquals("https://github.test/pr/1", result.path("html_url").asText());
    assertEquals("COMPLETED", store.get("events", pid).path("status").asText());
    server.verify(1, postRequestedFor(urlPathEqualTo("/repos/acme/platform/pulls"))
        .withRequestBody(matchingJsonPath("$.head", containing("connector/acme-"))));
  }

  @Test
  void proposePrRejectsNonGithubAndBadRepo() {
    var gh = connection("github", "alice");
    var notGh = connection("custom", "alice");
    var files = J.arrayNode().addObject().put("path", "a").put("content", "b");
    var input = J.objectNode()
        .put("githubConnectionId", notGh.path("id").asText())
        .put("repo", "acme/platform");
    input.set("files", files);
    var conn = connection("custom", "alice");
    assertThrows(IllegalArgumentException.class,
        () -> connectors.proposePr(conn.path("id").asText(), input, "alice"));
    var input2 = input.deepCopy();
    input2.put("githubConnectionId", gh.path("id").asText());
    input2.put("repo", "not-a-repo");
    assertThrows(IllegalArgumentException.class,
        () -> connectors.proposePr(conn.path("id").asText(), input2, "alice"));
    // files never leak the connector secret
    var secretish = J.objectNode()
        .put("githubConnectionId", gh.path("id").asText())
        .put("repo", "acme/platform").put("name", "x");
    var oneFile = J.arrayNode();
    oneFile.addObject().put("path", "f").put("content", "v");
    secretish.set("files", oneFile);
    var proposal = connectors.proposePr(conn.path("id").asText(), secretish, "alice");
    assertFalse(proposal.toString().contains("user:pass"));
  }

  // ---------- connector versions ----------

  @Test
  void connectorVersionsActivateSupersedesPrevious() {
    var conn = connection("custom", "alice");
    String connId = conn.path("id").asText();
    var v1 = connectors.saveVersion(connId, J.objectNode()
        .set("config", J.objectNode().put("chatPath", "/v1")), "alice");
    assertEquals("DRAFT", v1.path("status").asText());
    assertEquals(1, v1.path("version").asInt());
    // cannot activate DRAFT
    var v1id = v1.path("id").asText();
    assertThrows(IllegalArgumentException.class,
        () -> connectors.activate(v1id, "alice"));
    connectors.markValidated(v1id, "alice");
    var active1 = connectors.activate(v1id, "alice");
    assertEquals("ACTIVE", active1.path("status").asText());
    var v2 = connectors.saveVersion(connId, J.objectNode()
        .set("config", J.objectNode().put("chatPath", "/v2")), "alice");
    assertEquals(2, v2.path("version").asInt());
    connectors.markValidated(v2.path("id").asText(), "alice");
    connectors.activate(v2.path("id").asText(), "alice");
    var versions = connectors.versions(connId, "alice");
    assertEquals(2, versions.size());
    assertEquals("SUPERSEDED", versions.stream()
        .filter(v -> v.path("id").asText().equals(v1id)).findFirst().get()
        .path("status").asText());
    // activation propagated config onto the connection
    var after = store.get("connections", connId);
    assertEquals("/v2", after.path("connectorConfig").path("chatPath").asText());
    // bob cannot touch alice's versions
    assertThrows(org.springframework.security.access.AccessDeniedException.class,
        () -> connectors.markValidated(v1id, "bob"));
    assertThrows(org.springframework.security.access.AccessDeniedException.class,
        () -> connectors.versions(connId, "bob"));
  }

  // ---------- health ----------

  @Test
  void healthPanelAggregatesPerConnector() {
    var conn = connection("custom", "alice");
    conn.put("schemaHash", DriftMonitor.shapeHash(J.objectNode().put("a", "b")));
    conn = store.save("connections", conn, "alice");
    drift.onSuccess(conn, J.objectNode().put("a", "b"));
    drift.onFailure(conn);
    final String connId = conn.path("id").asText();
    var health = connectors.health("alice").stream()
        .filter(h -> connId.equals(h.path("connectionId").asText()))
        .findFirst().get();
    assertEquals(1, health.path("executions").asInt());
    assertEquals(1, health.path("failures").asInt());
    assertEquals(0.5, health.path("errorRate").asDouble(), 0.001);
    assertEquals("OK", health.path("health").asText()); // matching shape → no drift
    assertTrue(connectors.health("bob").stream()
        .noneMatch(h -> connId.equals(h.path("connectionId").asText())));
  }

  // ---------- catalog + cost limits ----------

  @Test
  void catalogSeedsReportAndRule() {
    var splunkConn = J.objectNode()
        .put("provider", "splunk").put("name", "splunk-alice")
        .put("baseUrl", server.baseUrl()).put("authType", "SPLUNK_TOKEN")
        .put("credential", "token-abc").put("deployment", "ENTERPRISE");
    splunkConn.putArray("approvedIndexes").add("main");
    var conn = connections.connect(splunkConn, "alice");
    server.stubFor(get(urlPathEqualTo("/services/server/info"))
        .willReturn(okJson("{\"entry\":[{\"content\":{\"version\":\"9.1.0\"}}]}")));
    var applyIn = J.objectNode();
    applyIn.putArray("connectionIds").add(conn.path("id").asText());
    var applied = connectors.applyCatalog("error-rate-alert", applyIn, "alice");
    assertFalse(applied.path("report").path("id").asText().isBlank());
    assertEquals(applied.path("report").path("id").asText(),
        applied.path("rule").path("reportId").asText());
    var catalog = connectors.catalog("alice");
    assertEquals(4, catalog.size());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> connectors.applyCatalog("nope", J.objectNode(), "alice"));
  }

  @Test
  void costLimitsCapConnectionLimits() {
    var conn = J.objectNode().put("provider", "splunk").put("name", "spl")
        .put("ownerId", String.valueOf(users.requireUsername("alice").get("id")))
        .put("owner", "alice").put("id", "conn-x");
    conn.putObject("limits").put("maxResults", 50000).put("maxConcurrentJobs", 2);
    conn = store.save("connections", conn, "alice");
    assertEquals(50000, splunk.limit(conn, "maxResults", 50000));
    var cl1 = J.objectNode().put("scope", "connection").put("targetId", "conn-x");
    cl1.set("limits", J.objectNode().put("maxResults", 100));
    connectors.saveCostLimit(cl1, "alice");
    assertEquals(100, splunk.limit(conn, "maxResults", 50000));
    var cl2 = J.objectNode().put("scope", "user");
    cl2.set("limits", J.objectNode().put("maxConcurrentJobs", 1).put("maxWindowHours", 1));
    connectors.saveCostLimit(cl2, "alice");
    assertEquals(1, splunk.limit(conn, "maxConcurrentJobs", 2));
    assertEquals(1, splunk.limit(conn, "maxWindowHours", 24));
    // owner isolation — bob never sees alice's limits
    assertTrue(connectors.costLimits("bob").stream()
        .noneMatch(c -> "conn-x".equals(c.path("targetId").asText())));
  }

  @Test
  void costLimitValidation() {
    assertThrows(IllegalArgumentException.class, () -> connectors.saveCostLimit(
        J.objectNode().put("scope", "bogus"), "alice"));
    var neg = J.objectNode().put("scope", "user");
    neg.set("limits", J.objectNode().put("maxResults", -1));
    assertThrows(IllegalArgumentException.class, () -> connectors.saveCostLimit(neg, "alice"));
    // connection scope requires owning the connection
    assertThrows(RuntimeException.class,
        () -> connectors.saveCostLimit(J.objectNode()
            .put("scope", "connection").put("targetId", "someone-elses"), "alice"));
  }
}
