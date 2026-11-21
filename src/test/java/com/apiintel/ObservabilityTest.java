package com.apiintel;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.apiintel.activity.ActivityLog;
import com.apiintel.alerts.AlertService;
import com.apiintel.chat.*;
import com.apiintel.integrations.*;
import com.apiintel.investigate.InvestigateService;
import com.apiintel.jira.JiraService;
import com.apiintel.knowledge.KnowledgeService;
import com.apiintel.network.NetworkGuard;
import com.apiintel.notify.NotifyService;
import com.apiintel.reports.ReportService;
import com.apiintel.schedule.ScheduleService;
import com.apiintel.schedule.SchedulerEngine;
import com.apiintel.security.PlatformUserService;
import com.apiintel.security.Vault;
import com.apiintel.splunk.SplunkClient;
import com.apiintel.splunk.SplunkService;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stage A backend coverage: Splunk connector (version routing, auth modes,
 * jobs, pagination, errors, concurrency), durable scheduler, alert
 * dedup/lifecycle, notification outbox, knowledge search/invalidation and
 * investigations — all owner-scoped on the real H2 migration mirror.
 */
class ObservabilityTest {

  static final JsonNodeFactory J = JsonNodeFactory.instance;

  WireMockServer server;
  JdbcTemplate db;
  RecordStore store;
  Vault vault;
  PlatformUserService users;
  IntegrationService integrations;
  ConnectionService connections;
  ApprovedHosts approved;
  com.apiintel.oauth.TokenRefresher refresher;
  JiraService jira;
  SplunkClient splunkClient;
  SplunkService splunk;
  ReportService reports;
  NotifyService notify;
  AlertService alerts;
  KnowledgeService knowledge;
  ScheduleService schedules;
  SchedulerEngine engine;
  InvestigateService investigate;
  ChatService chat;
  String aliceId, bobId;

  @BeforeEach
  void setup() {
    server = new WireMockServer(0);
    server.start();
    String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    store = new RecordStore(db, new com.fasterxml.jackson.databind.ObjectMapper());
    vault = new Vault(db, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    users = new PlatformUserService(db, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());
    users.bootstrap("admin-password-1", "developer-password-1", "reviewer-password-1");
    aliceId = String.valueOf(users.create("alice", "alice-password-1", "Alice", List.of("DEVELOPER"), true).get("id"));
    bobId = String.valueOf(users.create("bob", "bob-password-12", "Bob", List.of("DEVELOPER"), true).get("id"));
    var guard = org.mockito.Mockito.mock(NetworkGuard.class);
    org.mockito.Mockito.when(guard.validate(org.mockito.ArgumentMatchers.anyString()))
      .thenAnswer(a -> java.net.URI.create(a.getArgument(0)));
    approved = new ApprovedHosts(store, guard, "", "", "http://localhost:18080");
    integrations = new IntegrationService(store, vault, guard, approved, "http://localhost:18080");
    connections = new ConnectionService(store, vault, users, integrations, approved);
    refresher = new com.apiintel.oauth.TokenRefresher(store, vault, approved);
    refresher.setActivity(new ActivityLog(db));
    jira = new JiraService(store, connections, integrations, refresher);
    integrations.setPersonalExecutors(List.of(jira));
    splunkClient = new SplunkClient(vault, approved);
    splunk = new SplunkService(store, connections, users, vault, splunkClient);
    knowledge = new KnowledgeService(db, users, 7, 90);
    notify = new NotifyService(db, store, vault, users, approved, null, "");
    alerts = new AlertService(store, db, users, notify);
    reports = new ReportService(store, connections, users, splunk, db, List.of(alerts));
    schedules = new ScheduleService(db, users, reports);
    engine = new SchedulerEngine(db, schedules, reports, users, store, true);
    engine.setNotify(notify);
    var contexts = new ChatContextService(store,
        new ReplayService(store, vault, connections, approved), jira, connections, integrations);
    var adapters = new ChatAdapters(List.of(new TemplateChatAdapter(integrations,
        new MultiStepAdapter(integrations))));
    chat = new ChatService(db, store, users, connections, integrations, adapters, contexts,
        new ConfirmationGrants(), refresher);
    org.springframework.test.util.ReflectionTestUtils.setField(chat, "knowledge", knowledge);
    investigate = new InvestigateService(store, users, splunk, jira, knowledge, chat,
        new ConfirmationGrants(), db);
    stubSplunkBase();
  }

  @AfterEach
  void cleanup() {
    server.stop();
  }

  // ---------- fixture ----------

  void stubSplunkBase() {
    server.stubFor(get(urlPathEqualTo("/services/server/info"))
        .willReturn(okJson("{\"entry\":[{\"content\":{\"version\":\"9.1.0\"}}]}")));
    server.stubFor(post(urlEqualTo("/services/auth/login"))
        .willReturn(okJson("{\"sessionKey\":\"sess-abc\"}")));
    server.stubFor(get(urlPathEqualTo("/services/authentication/current-context"))
        .willReturn(okJson("{\"entry\":[{\"content\":{\"username\":\"svc\","
            + "\"roles\":[\"admin\"],\"srchJobsQuota\":10,\"srchDiskQuota\":250}}]}")));
    server.stubFor(post(urlPathEqualTo("/servicesNS/-/search/search/v2/jobs"))
        .willReturn(okJson("{\"sid\":\"sid-1\"}")));
    server.stubFor(get(urlPathEqualTo("/servicesNS/-/search/search/v2/jobs/sid-1"))
        .willReturn(okJson("{\"entry\":[{\"content\":{\"dispatchState\":\"DONE\","
            + "\"doneProgress\":1.0,\"resultCount\":1,\"isFailed\":false}}]}")));
    server.stubFor(get(urlPathEqualTo("/services/search/v2/jobs/sid-1/results"))
        .willReturn(okJson("{\"results\":[{\"_time\":\"1000\",\"svc_name\":\"checkout\","
            + "\"status\":\"200\",\"dur\":\"42\",\"password\":\"should-hide\"}]}")));
    server.stubFor(post(urlPathMatching("/servicesNS/-/search/search/(v2/)?jobs/sid-1/control"))
        .willReturn(okJson("{}")));
    server.stubFor(get(urlPathEqualTo("/servicesNS/-/search/saved/searches"))
        .willReturn(okJson("{\"entry\":[{\"name\":\"errors\",\"content\":{\"search\":\"search index=main | stats count\",\"eai:acl\":{\"app\":\"search\"}}}]}")));
  }

  void stubStats(String sid, String rowJson) {
    server.stubFor(post(urlPathEqualTo("/servicesNS/-/search/search/v2/jobs"))
        .willReturn(okJson("{\"sid\":\"" + sid + "\"}")));
    server.stubFor(get(urlPathEqualTo("/servicesNS/-/search/search/v2/jobs/" + sid))
        .willReturn(okJson("{\"entry\":[{\"content\":{\"dispatchState\":\"DONE\","
            + "\"doneProgress\":1.0,\"resultCount\":1,\"isFailed\":false}}]}")));
    server.stubFor(get(urlPathEqualTo("/services/search/v2/jobs/" + sid + "/results"))
        .willReturn(okJson("{\"results\":[" + rowJson + "]}")));
    server.stubFor(post(urlPathMatching("/servicesNS/-/search/search/(v2/)?jobs/" + sid + "/control"))
        .willReturn(okJson("{}")));
  }

  ObjectNode splunkConnection(String user) {
    return splunkConnection(user, "SPLUNK_TOKEN");
  }

  ObjectNode splunkConnection(String user, String authType) {
    var input = store.parse("{}")
        .put("provider", "splunk").put("name", "splunk-" + user)
        .put("baseUrl", server.baseUrl())
        .put("authType", authType)
        .put("deployment", "ENTERPRISE")
        .put("credential", authType.equals("SPLUNK_TOKEN") ? "tok-1" : "svc:pw")
        .put("defaultTimezone", "UTC");
    input.putArray("approvedIndexes").add("main");
    var fm = input.putObject("fieldMappings");
    fm.put("timestamp", "_time");
    fm.put("service", "svc_name");
    fm.put("httpStatus", "status");
    fm.put("durationMs", "dur");
    var limits = input.putObject("limits");
    limits.put("maxConcurrentJobs", 2);
    limits.put("maxResults", 4);
    limits.put("pageSize", 2);
    return connections.connect(input, user);
  }

  // ---------- splunk ----------

  @Test
  void validateReturnsVersionAndQuotas() {
    var conn = splunkConnection("alice");
    var result = splunk.validate(conn.path("id").asText(), "alice");
    assertTrue(result.path("authenticate").path("ok").asBoolean());
    assertEquals("9.1.0", result.path("version").asText());
    assertEquals("v2", result.path("apiVersion").asText());
    assertEquals("main", result.path("search").path("index").asText());
    assertTrue(result.path("retrieve").path("ok").asBoolean());
    assertEquals(10, result.path("quotas").path("srchJobsQuota").asInt());
    var saved = store.get("connections", conn.path("id").asText());
    assertEquals("v2", saved.path("apiVersion").asText());
  }

  @Test
  void oldVersionUsesV1Endpoints() {
    server.stubFor(get(urlPathEqualTo("/services/server/info"))
        .willReturn(okJson("{\"entry\":[{\"content\":{\"version\":\"8.2.0\"}}]}")));
    server.stubFor(post(urlPathEqualTo("/servicesNS/-/search/search/jobs"))
        .willReturn(okJson("{\"sid\":\"sid-v1\"}")));
    var conn = splunkConnection("alice");
    conn.put("apiVersion", "");
    String sid = splunk.createJob(conn, "search index=main | head 1", 100, 200);
    server.verify(postRequestedFor(urlPathEqualTo("/servicesNS/-/search/search/jobs")));
    assertEquals("v1", conn.path("apiVersion").asText());
    assertNotNull(sid);
  }

  @Test
  void v2Create404FallsBackToV1() {
    server.stubFor(post(urlPathEqualTo("/servicesNS/-/search/search/v2/jobs"))
        .willReturn(aResponse().withStatus(404)));
    server.stubFor(post(urlPathEqualTo("/servicesNS/-/search/search/jobs"))
        .willReturn(okJson("{\"sid\":\"sid-v1\"}")));
    var conn = splunkConnection("alice");
    conn.put("apiVersion", "v2");
    String sid = splunk.createJob(conn, "search index=main", 1, 2);
    assertEquals("sid-v1", sid);
    assertEquals("v1", conn.path("apiVersion").asText());
  }

  @Test
  void sessionAuthLogsInAndCaches() {
    var conn = splunkConnection("alice", "SESSION");
    String body = splunkClient.get("/services/server/info", conn, java.util.Map.of("output_mode", "json"));
    server.verify(postRequestedFor(urlEqualTo("/services/auth/login"))
        .withRequestBody(containing("username=svc")));
    server.verify(getRequestedFor(urlPathEqualTo("/services/server/info"))
        .withHeader("Authorization", equalTo("Splunk sess-abc")));
    assertFalse(body.isBlank());
  }

  @Test
  void sessionReloginsOnceOn401() {
    // first call 401 → login → retry once; persistent 401 → SPLUNK_AUTH
    server.stubFor(get(urlPathEqualTo("/services/authentication/current-context"))
        .willReturn(aResponse().withStatus(401)));
    var conn = splunkConnection("alice", "SESSION");
    var ex = assertThrows(SplunkClient.SplunkException.class,
        () -> splunkClient.get("/services/authentication/current-context", conn,
            java.util.Map.of("output_mode", "json")));
    assertEquals("SPLUNK_AUTH", ex.code);
    server.verify(1, postRequestedFor(urlEqualTo("/services/auth/login")));
    server.verify(2, getRequestedFor(urlPathEqualTo("/services/authentication/current-context")));
  }

  @Test
  void jobsPaginateAndTruncate() {
    server.stubFor(get(urlPathEqualTo("/services/search/v2/jobs/sid-1/results"))
        .withQueryParam("offset", equalTo("0"))
        .willReturn(okJson("{\"results\":[{\"a\":1},{\"a\":2}]}")));
    server.stubFor(get(urlPathEqualTo("/services/search/v2/jobs/sid-1/results"))
        .withQueryParam("offset", equalTo("2"))
        .willReturn(okJson("{\"results\":[{\"a\":3},{\"a\":4}]}")));
    var conn = splunkConnection("alice");
    conn.put("apiVersion", "v2");
    var page = splunk.results(conn, "sid-1", 0, 10, 4);
    assertEquals(4, page.results().size());
    assertTrue(page.truncated());
  }

  @Test
  void concurrencyLimitReturns429() {
    var conn = splunkConnection("alice");
    var input = store.parse("{}")
        .put("savedSearch", "errors").put("earliest", 1).put("latest", 2);
    splunk.createJob(conn.path("id").asText(), input.deepCopy(), "alice");
    splunk.createJob(conn.path("id").asText(), input.deepCopy(), "alice");
    var ex = assertThrows(ResponseStatusException.class,
        () -> splunk.createJob(conn.path("id").asText(), input.deepCopy(), "alice"));
    assertEquals(429, ex.getStatusCode().value());
    assertTrue(ex.getReason().contains("Concurrency limit"));
  }

  @Test
  void quotaAndPermissionErrorsAreStructured() {
    server.stubFor(post(urlPathEqualTo("/servicesNS/-/search/search/v2/jobs"))
        .willReturn(aResponse().withStatus(503).withBody("{\"messages\":[{\"text\":\"quota exceeded max concurrent searches\"}]}")));
    var conn = splunkConnection("alice");
    var ex = assertThrows(SplunkClient.SplunkException.class,
        () -> splunk.createJob(conn, "search index=main", 1, 2));
    assertEquals("SPLUNK_QUOTA", ex.code);
    server.stubFor(post(urlPathEqualTo("/servicesNS/-/search/search/v2/jobs"))
        .willReturn(aResponse().withStatus(403).withBody("{\"messages\":[{\"text\":\"no capability for index=main\"}]}")));
    ex = assertThrows(SplunkClient.SplunkException.class,
        () -> splunk.createJob(conn, "search index=main", 1, 2));
    assertEquals("SPLUNK_PERMISSION", ex.code);
    assertTrue(ex.getMessage().contains("index=main"));
  }

  @Test
  void cancelPostsControl() {
    var conn = splunkConnection("alice");
    conn.put("apiVersion", "v2");
    splunk.cancelSid(conn, "sid-1");
    server.verify(postRequestedFor(urlPathEqualTo("/servicesNS/-/search/search/v2/jobs/sid-1/control"))
        .withRequestBody(containing("action=cancel")));
  }

  @Test
  void templatesValidateIndexesAndCommands() {
    var conn = splunkConnection("alice");
    var tpl = store.parse("{}").put("connectionId", conn.path("id").asText())
        .put("name", "t1").put("spl", "search index=other | head 5");
    var ex = assertThrows(IllegalArgumentException.class,
        () -> splunk.saveTemplate(tpl, "alice"));
    assertTrue(ex.getMessage().contains("approvedIndexes"));
    tpl.put("spl", "search index=main | delete");
    assertThrows(IllegalArgumentException.class, () -> splunk.saveTemplate(tpl, "alice"));
    tpl.put("spl", "search index=main svc={{svc}} | head 5");
    tpl.set("params", store.parse("{\"svc\":{\"type\":\"string\",\"allow\":[\"checkout\",\"auth\"]}}"));
    var saved = splunk.saveTemplate(tpl, "alice");
    String rendered = splunk.renderTemplate(saved, store.parse("{\"svc\":\"checkout\"}"));
    assertTrue(rendered.contains("svc=checkout"));
    assertThrows(IllegalArgumentException.class,
        () -> splunk.renderTemplate(saved, store.parse("{\"svc\":\"evil\"}")));
  }

  @Test
  void mappingPreviewRedactsAndMaps() {
    var conn = splunkConnection("alice");
    var out = splunk.mappingPreview(conn.path("id").asText(), store.parse("{}"), "alice");
    var row = out.path("sample").get(0);
    assertEquals("checkout", row.path("mapped").path("service").asText());
    assertEquals("200", row.path("mapped").path("httpStatus").asText());
    assertTrue(row.path("raw").path("password").isMissingNode()); // secret field dropped
  }

  @Test
  void savedSearchesList() {
    var conn = splunkConnection("alice");
    var list = splunk.savedSearches(conn.path("id").asText(), "alice", 50);
    assertEquals("errors", list.get(0).path("name").asText());
  }

  // ---------- reports + snapshots ----------

  ObjectNode reportDef(String user, String connectionId) {
    var input = store.parse("{}").put("name", "weekly errors");
    if (connectionId != null) input.putArray("connectionIds").add(connectionId);
    var policy = input.putObject("policy");
    policy.put("preset", "LAST_1H").put("mode", "ROLLING").put("zone", "UTC")
        .put("bucketSeconds", 300).put("lagSeconds", 0).put("completedBucketsOnly", false);
    return reports.saveDefinition(input, user);
  }

  @Test
  void snapshotIsImmutableAndVersioned() {
    stubStats("s1", "{\"count\":\"100\",\"failures\":\"5\",\"successes\":\"95\",\"latest\":\"999\"}");
    var conn = splunkConnection("alice");
    var def = reportDef("alice", conn.path("id").asText());
    var snap = reports.run(def.path("id").asText(), Instant.now(), null, "alice");
    assertFalse(snap.path("id").asText().isBlank());
    var fetched = reports.snapshot(snap.path("id").asText(), "alice");
    assertEquals(snap.path("metrics").toString(), fetched.path("metrics").toString());
    // a second run creates a new row; the first is untouched
    var snap2 = reports.run(def.path("id").asText(), Instant.now(), null, "alice");
    assertNotEquals(snap.path("id").asText(), snap2.path("id").asText());
    assertEquals(2, reports.snapshots(def.path("id").asText(), "alice").size());
  }

  @Test
  void bobCannotSeeAliceSnapshotOrReport() {
    stubStats("s2", "{\"count\":\"1\",\"failures\":\"0\",\"successes\":\"1\",\"latest\":\"1\"}");
    var conn = splunkConnection("alice");
    var def = reportDef("alice", conn.path("id").asText());
    var snap = reports.run(def.path("id").asText(), Instant.now(), null, "alice");
    assertThrows(Exception.class, () -> reports.definition(def.path("id").asText(), "bob"));
    assertThrows(Exception.class, () -> reports.snapshot(snap.path("id").asText(), "bob"));
    assertThrows(Exception.class, () -> splunk.validate(conn.path("id").asText(), "bob"));
    // outbox and notifications are owner-scoped too
    notify.notifyOnce(aliceId, "k1", "t", "b");
    assertEquals(1, notify.notifications("alice").size());
    assertEquals(0, notify.notifications("bob").size());
  }

  @Test
  void csvAndPdfExports() {
    stubStats("s3", "{\"count\":\"7\",\"failures\":\"1\",\"successes\":\"6\",\"latest\":\"5\"}");
    var conn = splunkConnection("alice");
    var def = reportDef("alice", conn.path("id").asText());
    var snap = reports.run(def.path("id").asText(), Instant.now(), null, "alice");
    var exports = new com.apiintel.reports.ReportExports();
    String csv = new String(exports.csv(reports.snapshot(snap.path("id").asText(), "alice")));
    assertTrue(csv.contains("requests"));
    byte[] pdf = exports.pdf(reports.snapshot(snap.path("id").asText(), "alice"));
    assertTrue(pdf.length > 200 && pdf[0] == '%' && pdf[1] == 'P');
  }

  // ---------- scheduler ----------

  private void drain(String runId, SchedulerEngine e) throws Exception {
    for (int i = 0; i < 50; i++) {
      var run = schedules.runById(runId);
      if (run != null && !List.of("QUEUED", "LEASED").contains(run.path("status").asText())) return;
      e.tick();
      Thread.sleep(100);
    }
    fail("run " + runId + " never finished: " + schedules.runById(runId));
  }

  @Test
  void scheduleRunsOnceAcrossTwoEngines() throws Exception {
    var def = reportDef("alice", null);
    var engine2 = new SchedulerEngine(db, schedules, reports, users, store, true);
    var s = schedules.save(store.parse("{}")
        .put("reportId", def.path("id").asText()).put("kind", "INTERVAL").put("expr", "60")
        .put("zone", "UTC"), "alice");
    // force the next run into the past so materialize emits it
    db.update("UPDATE schedules SET next_run_at=? WHERE id=?",
        Instant.now().minusSeconds(120).toString(), s.path("id").asText());
    engine.materialize(Instant.now());
    engine2.materialize(Instant.now()); // second engine must not duplicate
    var runs = schedules.history(s.path("id").asText(), "alice");
    assertEquals(1, runs.size());
    engine.tick();
    engine2.tick();
    Thread.sleep(800);
    var done = schedules.history(s.path("id").asText(), "alice");
    assertEquals(1, done.stream().filter(r -> "SUCCEEDED".equals(r.path("status").asText())).count());
  }

  @Test
  void expiredLeaseIsReclaimed() throws Exception {
    var def = reportDef("alice", null);
    var s = schedules.save(store.parse("{}")
        .put("reportId", def.path("id").asText()).put("kind", "INTERVAL").put("expr", "60"), "alice");
    String runId = UUID.randomUUID().toString();
    db.update("INSERT INTO schedule_runs(id,schedule_id,owner_id,execution_key,planned_at,status,"
        + "lease_owner,lease_expires_at) VALUES(?,?,?,?,?,'LEASED',?,?)",
        runId, s.path("id").asText(), aliceId, s.path("id").asText() + "|" + Instant.now().getEpochSecond(),
        Instant.now().toString(), "crashed-engine", Instant.now().minusSeconds(120).toString());
    drain(runId, engine);
    assertEquals("SUCCEEDED", schedules.runById(runId).path("status").asText());
    assertNotNull(schedules.runById(runId).path("snapshotId").asText(null));
  }

  @Test
  void missedRunPolicies() {
    var def = reportDef("alice", null);
    var skip = schedules.save(store.parse("{}")
        .put("reportId", def.path("id").asText()).put("kind", "INTERVAL").put("expr", "30")
        .put("missedPolicy", "SKIP"), "alice");
    var capped = schedules.save(store.parse("{}")
        .put("reportId", def.path("id").asText()).put("kind", "INTERVAL").put("expr", "30")
        .put("missedPolicy", "RUN_ALL_CAPPED").put("maxCatchup", 2), "alice");
    Instant past = Instant.now().minusSeconds(300);
    db.update("UPDATE schedules SET next_run_at=? WHERE id IN (?,?)",
        past.toString(), skip.path("id").asText(), capped.path("id").asText());
    engine.materialize(Instant.now());
    assertEquals(1, schedules.history(skip.path("id").asText(), "alice").size());
    assertEquals(2, schedules.history(capped.path("id").asText(), "alice").size());
  }

  @Test
  void retryBackoffThenDead() throws Exception {
    // report bound to a splunk connection that always 403s → ACCESS_REVOKED
    server.stubFor(get(urlPathEqualTo("/services/server/info"))
        .willReturn(aResponse().withStatus(403)));
    var conn = splunkConnection("alice");
    var def = reportDef("alice", conn.path("id").asText());
    var s = schedules.save(store.parse("{}")
        .put("reportId", def.path("id").asText()).put("kind", "INTERVAL").put("expr", "60")
        .put("retryLimit", 1).put("backoffBaseS", 1), "alice");
    String runId = UUID.randomUUID().toString();
    db.update("INSERT INTO schedule_runs(id,schedule_id,owner_id,execution_key,planned_at,status)"
        + " VALUES(?,?,?,?,?,'QUEUED')",
        runId, s.path("id").asText(), aliceId, "k|1", Instant.now().toString());
    // first attempt fails → requeued with backoff
    engine.tick(); Thread.sleep(600);
    var run = schedules.runById(runId);
    assertEquals("QUEUED", run.path("status").asText());
    assertTrue(run.path("error").asText().contains("ACCESS_REVOKED"));
    // next_attempt_at in the past → second attempt → DEAD
    db.update("UPDATE schedule_runs SET next_attempt_at=? WHERE id=?",
        Instant.now().minusSeconds(1).toString(), runId);
    engine.tick(); Thread.sleep(600);
    assertEquals("DEAD", schedules.runById(runId).path("status").asText());
    // owner notified exactly once
    assertEquals(1, notify.notifications("alice").stream()
        .filter(n -> String.valueOf(n.get("title") == null ? n.get("TITLE") : n.get("title"))
            .contains("credentials")).count());
    // connection marked reconnect
    assertEquals("RECONNECT_REQUIRED",
        store.get("connections", conn.path("id").asText()).path("state").asText());
  }

  @Test
  void deadlineEnforced() throws Exception {
    var def = reportDef("alice", null);
    var s = schedules.save(store.parse("{}")
        .put("reportId", def.path("id").asText()).put("kind", "INTERVAL").put("expr", "60")
        .put("deadlineS", 0).put("retryLimit", 0), "alice");
    String runId = UUID.randomUUID().toString();
    db.update("INSERT INTO schedule_runs(id,schedule_id,owner_id,execution_key,planned_at,status)"
        + " VALUES(?,?,?,?,?,'QUEUED')",
        runId, s.path("id").asText(), aliceId, "k|2", Instant.now().toString());
    engine.tick(); Thread.sleep(600);
    assertEquals("DEAD", schedules.runById(runId).path("status").asText());
    assertTrue(schedules.runById(runId).path("error").asText().contains("DEADLINE"));
  }

  @Test
  void schedulePreviewIsDstAware() {
    var times = schedules.preview("DAILY", "09:00", "America/New_York", 5);
    assertEquals(5, times.size());
    // all at 09:00 local regardless of DST
    for (var t : times) {
      var z = Instant.parse(t.asText()).atZone(java.time.ZoneId.of("America/New_York"));
      assertEquals(9, z.getHour());
    }
  }

  // ---------- alerts ----------

  private ObjectNode breachingSnapshot(String ownerId, String reportId, double errorRate, String epoch) {
    var snap = J.objectNode();
    snap.put("id", UUID.randomUUID().toString());
    snap.put("reportId", reportId);
    snap.put("ownerId", ownerId);
    snap.put("evaluatedAt", Instant.ofEpochSecond(Long.parseLong(epoch)).toString());
    snap.put("completeness", "COMPLETE");
    var m = snap.putArray("metrics");
    m.addObject().put("name", "requests").put("current", 300).put("baseline", 300)
        .put("status", "MEASURED");
    m.addObject().put("name", "error_rate").put("current", errorRate).put("baseline", 0.02)
        .put("ppChange", errorRate - 0.02).put("status", "MEASURED");
    snap.putArray("errors");
    snap.putObject("resolvedWindows").putObject("current").put("startEpoch", epoch);
    snap.putObject("series");
    return snap;
  }

  private ObjectNode exampleRule(String reportId) {
    // errorRate > 5% AND requests >= 200 AND pp change >= 2pp
    var input = store.parse("{}").put("reportId", reportId).put("name", "err rate");
    var conds = input.putArray("conditions");
    conds.addObject().put("type", "ABSOLUTE").put("metric", "error_rate").put("op", ">").put("value", 0.05);
    conds.addObject().put("type", "ABSOLUTE").put("metric", "requests").put("op", ">=").put("value", 200);
    conds.addObject().put("type", "RATE_CHANGE").put("metric", "error_rate").put("op", ">=").put("value", 0.02);
    input.put("consecutive", 1).put("notifyRecovery", true);
    return alerts.saveRule(input, "alice");
  }

  @Test
  void alertFiresOnceAcrossRepeatedBreaches() {
    var rule = exampleRule("r1");
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r1", 0.09, "1000000"), false);
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r1", 0.09, "1000000"), false);
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r1", 0.12, "1000000"), false);
    var out = db.queryForList("SELECT * FROM notification_outbox");
    assertEquals(1, out.size());
    var inc = db.queryForList("SELECT * FROM alert_incidents");
    assertEquals(1, inc.size());
  }

  @Test
  void consecutiveThresholdDelaysFiring() {
    var rule = exampleRule("r2");
    rule = alerts.saveRule(store.parse(rule.toString()).put("consecutive", 3), "alice");
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r2", 0.09, "1000000"), false);
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r2", 0.09, "1000000"), false);
    assertTrue(db.queryForList("SELECT * FROM notification_outbox").isEmpty());
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r2", 0.09, "1000000"), false);
    assertEquals(1, db.queryForList("SELECT * FROM notification_outbox").size());
  }

  @Test
  void recoveryNotificationAfterCleanEvals() {
    var rule = exampleRule("r3");
    rule.path("recovery"); // default
    rule = alerts.saveRule(store.parse(rule.toString()), "alice");
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r3", 0.09, "1000000"), false);
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r3", 0.01, "1000000"), false);
    var incidents = db.queryForList("SELECT * FROM alert_incidents");
    assertEquals("RESOLVED", String.valueOf(
        incidents.get(0).get("state") == null ? incidents.get(0).get("STATE") : incidents.get(0).get("state")));
    // open + recovery notifications
    assertEquals(2, db.queryForList("SELECT * FROM in_app_notifications").size());
  }

  @Test
  void maintenanceWindowSuppresses() {
    var input = store.parse(exampleRule("r4").toString());
    var mw = input.putArray("maintenanceWindows");
    mw.addObject().put("start", Instant.now().minusSeconds(99999).toString())
        .put("end", Instant.now().plusSeconds(99999).toString());
    var rule = alerts.saveRule(input, "alice");
    var snap = breachingSnapshot(aliceId, "r4", 0.09,
        String.valueOf(Instant.now().getEpochSecond()));
    alerts.evaluate(rule, snap, false);
    assertTrue(db.queryForList("SELECT * FROM notification_outbox").isEmpty());
    var incidents = db.queryForList("SELECT * FROM alert_incidents");
    assertEquals("SUPPRESSED", String.valueOf(
        incidents.get(0).get("state") == null ? incidents.get(0).get("STATE") : incidents.get(0).get("state")));
  }

  @Test
  void missingDataIsDistinctKind() {
    var input = store.parse("{}").put("reportId", "r5").put("name", "no traffic");
    input.putArray("conditions").addObject()
        .put("type", "MISSING_TRAFFIC").put("metric", "requests").put("minExpected", 100);
    var rule = alerts.saveRule(input, "alice");
    var snap = breachingSnapshot(aliceId, "r5", 0.0, "2000000");
    ((com.fasterxml.jackson.databind.node.ObjectNode) snap.path("metrics").get(0))
        .put("status", "MISSING");
    alerts.evaluate(rule, snap, false);
    var incidents = db.queryForList("SELECT * FROM alert_incidents");
    assertEquals("MISSING_DATA", String.valueOf(
        incidents.get(0).get("kind") == null ? incidents.get(0).get("KIND") : incidents.get(0).get("kind")));
  }

  @Test
  void cooldownSuppressesNewEpisode() {
    var input = store.parse(exampleRule("r6").toString());
    input.put("cooldownMinutes", 60);
    var rule = alerts.saveRule(input, "alice");
    long t = Instant.now().getEpochSecond();
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r6", 0.09, String.valueOf(t)), false);
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r6", 0.01, String.valueOf(t + 60)), false); // resolves
    assertEquals(1, db.queryForList("SELECT * FROM notification_outbox WHERE dedup_key LIKE '%|open'").size());
    // new breach 5 min later — within 60m cooldown → suppressed, no third notification
    alerts.evaluate(rule, breachingSnapshot(aliceId, "r6", 0.09, String.valueOf(t + 300)), false);
    assertEquals(2, db.queryForList("SELECT * FROM notification_outbox").size());
    var incidents = db.queryForList("SELECT * FROM alert_incidents WHERE state='SUPPRESSED'");
    assertEquals(1, incidents.size());
  }

  // ---------- outbox ----------

  @Test
  void outboxRetriesThenDead() {
    var dest = notify.saveDestination(store.parse("{}")
        .put("type", "WEBHOOK").put("name", "hook").put("url", server.baseUrl() + "/hook"), "alice");
    assertFalse(dest.path("enabled").asBoolean()); // host approved only on enable (opt-in)
    server.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(500)));
    notify.enqueue(aliceId, "dedup-1", dest.path("id").asText(), J.objectNode().put("title", "t"));
    notify.dispatch();
    var preEnable = db.queryForList("SELECT status FROM notification_outbox WHERE dedup_key='dedup-1'");
    assertNotEquals("SENT", String.valueOf(
        preEnable.get(0).get("status") == null ? preEnable.get(0).get("STATUS") : preEnable.get(0).get("status")));
    dest = notify.enableDestination(dest.path("id").asText(), store.parse("{\"confirm\":true}"), "alice");
    notify.enqueue(aliceId, "dedup-1", dest.path("id").asText(), J.objectNode().put("title", "t")); // dup
    for (int i = 0; i < 6; i++) {
      db.update("UPDATE notification_outbox SET next_attempt_at=NULL WHERE dedup_key='dedup-1'");
      notify.dispatch();
    }
    var rows = db.queryForList("SELECT * FROM notification_outbox");
    assertEquals(1, rows.size());
    assertEquals("DEAD", String.valueOf(
        rows.get(0).get("status") == null ? rows.get(0).get("STATUS") : rows.get(0).get("status")));
  }

  @Test
  void webhookSendsIdempotencyKey() {
    var dest = notify.saveDestination(store.parse("{}")
        .put("type", "WEBHOOK").put("name", "hook").put("url", server.baseUrl() + "/ok"), "alice");
    dest = notify.enableDestination(dest.path("id").asText(), store.parse("{\"confirm\":true}"), "alice");
    server.stubFor(post(urlEqualTo("/ok")).willReturn(okJson("{}")));
    notify.enqueue(aliceId, "idem-1", dest.path("id").asText(), J.objectNode().put("title", "t"));
    notify.dispatch();
    server.verify(postRequestedFor(urlEqualTo("/ok"))
        .withHeader("Idempotency-Key", matching(".+")));
    assertEquals("SENT", String.valueOf(db.queryForList(
        "SELECT status FROM notification_outbox").get(0).values().iterator().next()));
  }

  // ---------- knowledge ----------

  @Test
  void knowledgeLikeSearchOnH2() {
    assertFalse(knowledge.ftsEnabled()); // H2 → LIKE fallback
    knowledge.index(aliceId, "INCIDENT_RESOLUTION", "Checkout timeout",
        "resolved by restarting gateway", J.arrayNode(), null);
    knowledge.indexChatMessage(aliceId, "conv-1", "msg-1", "assistant", "the deploy fixed checkout");
    var hits = knowledge.search("alice", "checkout", List.of());
    assertEquals(2, hits.size());
    assertTrue(knowledge.search("bob", "checkout", List.of()).isEmpty());
    var chatOnly = knowledge.search("alice", "checkout", List.of("CHAT_MESSAGE"));
    assertEquals(1, chatOnly.size());
  }

  @Test
  void knowledgeInvalidationOnSourceDeletion() {
    var refs = J.arrayNode().add(knowledge.ref("REPORT", "rep-1", "3", "h1"));
    var item = knowledge.index(aliceId, "REPORT_SUMMARY", "summary", "body", refs, "h1");
    assertEquals(1, knowledge.getChecked(item.path("id").asText(), "alice").path("item").path("valid").asInt());
    knowledge.invalidate("REPORT", "rep-1");
    var checked = knowledge.getChecked(item.path("id").asText(), "alice");
    assertTrue(checked.path("stale").asBoolean());
    // bob cannot see alice's item at all
    assertTrue(knowledge.getChecked(item.path("id").asText(), "bob").path("stale").asBoolean());
  }

  // ---------- investigations ----------

  @Test
  void investigationEvidenceIsBoundedAndCitationCheckWorks() {
    stubStats("s9", "{\"count\":\"3\",\"failures\":\"1\",\"successes\":\"2\",\"latest\":\"9\"}");
    var conn = splunkConnection("alice");
    var def = reportDef("alice", conn.path("id").asText());
    var snap = reports.run(def.path("id").asText(), Instant.now(), null, "alice");
    var inv = investigate.create(store.parse("{}")
        .put("snapshotId", snap.path("id").asText())
        .put("connectionId", conn.path("id").asText())
        .put("service", "checkout"), "alice");
    assertTrue(inv.path("evidence").size() >= 2);
    assertTrue(inv.path("evidenceBytes").asInt() <= 40_500);
    assertEquals("E1", inv.path("evidence").get(0).path("id").asText());
    // citation check
    var check = investigate.citationCheck(
        "## Observations\nError rate rose [E1]. It was bad.\n## Possible causes\nx [E99]",
        java.util.Set.of("E1", "E2"));
    assertTrue(check.path("unknownCitations").toString().contains("E99"));
    assertFalse(check.path("ok").asBoolean());
    assertTrue(check.path("uncitedObservationSentences").toString().contains("It was bad"));
  }

  @Test
  void jiraDraftGoesThroughProposalBoundToUserConnection() {
    server.stubFor(get(urlPathMatching("/rest/api/[23]/myself"))
        .willReturn(okJson("{\"displayName\":\"Alice\"}")));
    var jiraConn = connections.connect(store.parse("{}")
        .put("provider", "jira").put("name", "jira-alice")
        .put("baseUrl", server.baseUrl()).put("authType", "BASIC")
        .put("credential", "alice@x.com:tok"), "alice");
    var inv = investigate.create(store.parse("{}").put("service", "checkout"), "alice");
    var proposal = investigate.jiraDraft(inv.path("id").asText(), store.parse("{}"), "alice");
    assertEquals("PENDING", proposal.path("status").asText());
    assertEquals(jiraConn.path("id").asText(), proposal.path("connectionId").asText());
    // proposal is a reviewed write — not yet executed
    assertTrue(store.list("events").stream()
        .anyMatch(e -> e.path("id").asText().equals(proposal.path("id").asText())));
  }

  // ---------- controllers: thin owner-scoped delegates ----------

  @Test
  void controllersDelegateToOwnerScopedServices() {
    java.security.Principal alice = () -> "alice";

    // splunk
    var splunkCtl = new com.apiintel.splunk.SplunkController(splunk);
    var conn = splunkConnection("alice");
    String connId = conn.path("id").asText();
    assertTrue(splunkCtl.validate(connId, alice).path("authenticate").path("ok").asBoolean());
    assertFalse(splunkCtl.savedSearches(connId, 10, alice).isEmpty());
    assertTrue(splunkCtl.mappingPreview(connId, null, alice).path("sample").size() > 0);
    var tpl = splunkCtl.saveTemplate(store.parse("{}")
        .put("connectionId", connId).put("name", "t")
        .put("spl", "search index=main | head 3"), alice);
    assertEquals(1, splunkCtl.templates(alice).size());
    splunkCtl.deleteTemplate(tpl.path("id").asText(), alice);
    var job = splunkCtl.createJob(connId, store.parse("{}")
        .put("savedSearch", "errors").put("earliest", 1).put("latest", 2), alice);
    String jobId = job.path("jobId").asText();
    assertEquals("DONE", splunkCtl.jobStatus(jobId, alice).path("state").asText());
    assertTrue(splunkCtl.jobResults(jobId, 0, 10, alice).path("count").asInt() > 0);
    assertEquals("CANCELLED", splunkCtl.cancelJob(jobId, alice).path("state").asText());
    var quota = splunkCtl.splunkError(new SplunkClient.SplunkException(503, "SPLUNK_QUOTA", "q"));
    assertEquals("RETRY", quota.getBody().path("action").asText());
    assertEquals(503, quota.getStatusCode().value());
    var auth = splunkCtl.splunkError(new SplunkClient.SplunkException(401, "SPLUNK_AUTH", "a"));
    assertEquals("RECONNECT", auth.getBody().path("action").asText());

    // reports
    var reportCtl = new com.apiintel.reports.ReportController(
        reports, new com.apiintel.reports.ReportExports());
    var defIn = store.parse("{}").put("name", "ctl report");
    defIn.putObject("policy").put("preset", "LAST_1H").put("mode", "ROLLING").put("zone", "UTC")
        .put("bucketSeconds", 300).put("lagSeconds", 0).put("completedBucketsOnly", false);
    var def = reportCtl.save(defIn, alice);
    String defId = def.path("id").asText();
    assertFalse(reportCtl.list(alice).isEmpty());
    assertEquals(defId, reportCtl.get(defId, alice).path("id").asText());
    var snap = reportCtl.run(defId, alice);
    String snapId = snap.path("id").asText();
    assertEquals(snapId, reportCtl.snapshot(snapId, alice).path("id").asText());
    assertFalse(reportCtl.snapshots(defId, alice).isEmpty());
    assertNotNull(reportCtl.preview(store.parse("{}").put("reportId", defId), alice));
    assertTrue(reportCtl.csv(snapId, alice).getBody().length > 10);
    assertTrue(reportCtl.pdf(snapId, alice).getBody().length > 100);
    reportCtl.delete(defId, alice);

    // schedules
    var schedCtl = new com.apiintel.schedule.ScheduleController(schedules);
    var def2 = reportDef("alice", null);
    var s = schedCtl.save(store.parse("{}")
        .put("reportId", def2.path("id").asText()).put("kind", "INTERVAL")
        .put("expr", "60").put("zone", "UTC"), alice);
    String schedId = s.path("id").asText();
    assertFalse(schedCtl.list(alice).isEmpty());
    assertEquals(schedId, schedCtl.get(schedId, alice).path("id").asText());
    assertFalse(schedCtl.preview("DAILY", "09:00", "UTC", 3).isEmpty());
    assertEquals("PAUSED", schedCtl.pause(schedId, alice).path("status").asText());
    assertEquals("ACTIVE", schedCtl.resume(schedId, alice).path("status").asText());
    var run = schedCtl.runNow(schedId, alice);
    assertFalse(schedCtl.history(schedId, alice).isEmpty());
    assertEquals("CANCELLED",
        schedCtl.cancelRun(run.path("id").asText(), alice).path("status").asText());
    schedCtl.delete(schedId, alice);

    // alerts
    var alertCtl = new com.apiintel.alerts.AlertController(alerts);
    var ruleIn = store.parse("{}").put("reportId", "rc").put("name", "c");
    ruleIn.putArray("conditions").addObject()
        .put("type", "ABSOLUTE").put("metric", "error_rate").put("op", ">").put("value", 0.5);
    var rule = alertCtl.save(ruleIn, alice);
    assertFalse(alertCtl.rules(alice).isEmpty());
    assertNotNull(alertCtl.backtest(rule.path("id").asText(), alice));
    var rule2 = exampleRule("rc2");
    alerts.evaluate(rule2, breachingSnapshot(aliceId, "rc2", 0.09, "3000000"), false);
    assertFalse(alertCtl.incidents(alice).isEmpty());
    String incId = alertCtl.incidents(alice).get(0).path("id").asText();
    assertEquals(incId, alertCtl.incident(incId, alice).path("id").asText());
    alertCtl.delete(rule2.path("id").asText(), alice);
    alertCtl.delete(rule.path("id").asText(), alice);

    // notifications
    var notifyCtl = new com.apiintel.notify.NotifyController(notify);
    var dest = notifyCtl.save(store.parse("{}")
        .put("type", "IN_APP").put("name", "inbox"), alice);
    String destId = dest.path("id").asText();
    assertTrue(notifyCtl.preview(destId, alice).has("payload"));
    assertThrows(IllegalArgumentException.class, () -> notifyCtl.enable(destId, null, alice));
    assertTrue(notifyCtl.enable(destId, store.parse("{\"confirm\":true}"), alice)
        .path("enabled").asBoolean());
    assertFalse(notifyCtl.disable(destId, alice).path("enabled").asBoolean());
    assertFalse(notifyCtl.destinations(alice).isEmpty());
    notify.notifyOnce(aliceId, "ck", "t", "b");
    var notes = notifyCtl.notifications(alice);
    assertFalse(notes.isEmpty());
    notifyCtl.read(String.valueOf(com.apiintel.store.Rows.get(notes.get(0), "id")), alice);
    assertNotNull(notifyCtl.outbox(alice));

    // investigations
    var invCtl = new com.apiintel.investigate.InvestigateController(investigate);
    var inv = invCtl.create(store.parse("{}").put("service", "checkout"), alice);
    String invId = inv.path("id").asText();
    assertFalse(invCtl.list(alice).isEmpty());
    assertEquals(invId, invCtl.get(invId, alice).path("id").asText());
    assertThrows(IllegalArgumentException.class,
        () -> invCtl.preview(invId, store.parse("{}"), alice));
    assertThrows(IllegalArgumentException.class, () -> invCtl.jiraDraft(invId, null, alice));
    invCtl.resolve(invId, store.parse("{}").put("resolution", "done"), alice);
    assertEquals("RESOLVED", invCtl.get(invId, alice).path("status").asText());

    // knowledge
    var knowCtl = new com.apiintel.knowledge.KnowledgeController(knowledge);
    knowledge.index(aliceId, "INVESTIGATION", "ctl-title", "ctl body", J.arrayNode(), null);
    assertFalse(knowCtl.search("ctl-title", null, alice).isEmpty());
    String kid = knowCtl.search("ctl-title", null, alice).get(0).path("id").asText();
    assertTrue(knowCtl.get(kid, alice).has("item"));
  }

  @Test
  void resolvePersistsKnowledge() {
    var inv = investigate.create(store.parse("{}").put("service", "checkout"), "alice");
    investigate.resolve(inv.path("id").asText(), store.parse("{}")
        .put("resolution", "Rolled back bad deploy"), "alice");
    var hits = knowledge.search("alice", "Rolled back", List.of("INCIDENT_RESOLUTION"));
    assertEquals(1, hits.size());
    assertEquals("RESOLVED", investigate.get(inv.path("id").asText(), "alice").path("status").asText());
  }
}
