package com.apiintel;

import static org.junit.jupiter.api.Assertions.*;

import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * End-to-end journey over a file-backed SQLite database with real Flyway
 * migrations, real form-login sessions and a real Spring restart in the
 * middle. Provider traffic goes to a local fixture server; the browser is a
 * fake {@link BrowserLauncher} injected before the context starts.
 */
class UnifiedJourneyE2ETest {

  static final String VAULT_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  static final ObjectMapper JSON = new ObjectMapper();
  static FakeLauncher launcher;
  static HttpServer fixture;
  static String fixtureBase;
  static final AtomicInteger expiredHits = new AtomicInteger();

  /** Fake studio browser: records callbacks so the test can emit captures/actions. */
  static class FakeLauncher implements BrowserLauncher {
    final AtomicInteger opens = new AtomicInteger();
    BrowserLauncher.OpenRequest request;
    String recorder;

    @Override
    public BrowserHandle open(OpenRequest req) {
      opens.incrementAndGet();
      this.request = req;
      return new BrowserHandle() {
        @Override public String currentUrl() { return fixtureBase + "/app"; }
        @Override public Object evaluate(String script) { return fixtureBase + "/app"; }
        @Override public void installRecorder(String js) { recorder = js; }
        @Override public String storageState() { return "{\"cookies\":[]}"; }
        @Override public String identity() { return "alice@fixture.example"; }
        @Override public void close() { }
      };
    }

    void emitCapture(ObjectNode capture) { request.onCapture().accept(capture); }
    void emitAction(ObjectNode action) { request.onAction().accept(action); }
  }

  static void startFixture() throws IOException {
    fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fixture.createContext("/probe", ex -> respond(ex, 200, "application/json", "{}"));
    fixture.createContext("/chat", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String prompt = body.contains("Connectivity check") ? "OK" : "fixture reply";
      respond(ex, 200, "application/json", "{\"response\":\"" + prompt + "\"}");
    });
    fixture.createContext("/expired", ex -> {
      if (expiredHits.incrementAndGet() == 1) {
        respond(ex, 401, "application/json", "{\"error\":\"expired\"}");
      } else {
        respond(ex, 200, "application/json", "{\"ok\":true}");
      }
    });
    fixture.createContext("/always401", ex -> respond(ex, 401, "application/json", "{}"));
    fixture.start();
    fixtureBase = "http://127.0.0.1:" + fixture.getAddress().getPort();
  }

  static void respond(com.sun.net.httpserver.HttpExchange ex, int status,
      String type, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
  }

  @AfterAll
  static void stopFixture() { if (fixture != null) fixture.stop(0); }

  /** Authenticated session: cookies (JSESSIONID + XSRF-TOKEN) + CSRF header. */
  static class Client {
    final String base;
    final HttpClient http;
    String csrf = "";

    Client(int port) {
      base = "http://127.0.0.1:" + port;
      http = HttpClient.newBuilder()
        .cookieHandler(new CookieManager())
        .connectTimeout(Duration.ofSeconds(10)).build();
    }

    private HttpRequest.Builder req(String path) {
      return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
    }

    String csrf() throws Exception {
      var resp = http.send(req("/api/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
      csrf = JSON.readTree(resp.body()).path("token").asText();
      return csrf;
    }

    void login(String user, String password) throws Exception {
      csrf();
      var resp = http.send(req("/api/login")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("X-XSRF-TOKEN", csrf)
        .POST(HttpRequest.BodyPublishers.ofString(
          "username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)))
        .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(204, resp.statusCode(), "login failed for " + user);
      csrf();
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

  ConfigurableApplicationContext startApp(Path dbFile, Path profiles) {
    launcher = new FakeLauncher();
    return new SpringApplicationBuilder(PlatformApplication.class)
      .initializers((ApplicationContextInitializer<GenericApplicationContext>) ctx -> {
        ctx.registerBean("e2eLauncher", BrowserLauncher.class, () -> launcher,
          def -> def.setPrimary(true));
        // Loopback fixture traffic is allowed; everything else keeps the real policy.
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

  @Test
  void unifiedJourneySurvivesRestart() throws Exception {
    startFixture();
    Path dir = Files.createTempDirectory("e2e-platform");
    Path dbFile = dir.resolve("platform.sqlite");
    Path profiles = dir.resolve("profiles");

    String conversationId;
    String aliceConnectionId;
    String studioSessionId;

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

      // Both users connect their own account to the same provider.
      java.util.function.Function<String, String> connectBody = credential -> {
        var node = JSON.createObjectNode()
          .put("provider", "custom").put("name", "Fixture Chat")
          .put("baseUrl", fixtureBase).put("authType", "BEARER")
          .put("credential", credential)
          .put("aiChatEnabled", true).put("chatPath", "/chat")
          .put("aiProbePath", "/probe").put("aiResponsePointer", "/response")
          .put("aiResponseFormat", "json");
        node.putObject("aiRequestTemplate").put("prompt", "{{prompt}}");
        return node.toString();
      };
      var aliceConn = alice.call("POST", "/api/connections", connectBody.apply("alice-secret-1"));
      assertEquals("CONNECTED", aliceConn.path("state").asText());
      aliceConnectionId = aliceConn.path("id").asText();
      var bobConn = bob.call("POST", "/api/connections", connectBody.apply("bob-secret-9"));
      assertEquals("CONNECTED", bobConn.path("state").asText());

      // Studio session through the fake launcher.
      var session = alice.call("POST", "/api/studio/sessions",
        "{\"connectionId\":\"" + aliceConnectionId + "\",\"url\":\"" + fixtureBase + "\"}");
      studioSessionId = session.path("id").asText();
      for (int i = 0; i < 40 && launcher.request == null; i++) Thread.sleep(100);
      assertNotNull(launcher.request, "studio browser never opened");
      for (int i = 0; i < 40; i++) {
        var state = alice.call("GET", "/api/studio/sessions/" + studioSessionId, null);
        if ("WAITING_FOR_SIGN_IN".equals(state.path("status").asText())) break;
        Thread.sleep(100);
      }
      alice.call("POST", "/api/studio/sessions/" + studioSessionId + "/signed-in", null);
      alice.call("POST", "/api/studio/sessions/" + studioSessionId + "/record/start",
        "{\"promptMarker\":\"probe-marker-1\"}");
      var capture = JSON.createObjectNode();
      capture.put("method", "POST").put("url", fixtureBase + "/chat")
        .put("status", 200).put("contentType", "application/json")
        .put("body", "{\"prompt\":\"probe-marker-1\",\"conversationId\":\"conv-9\"}")
        .put("responsePreview", "{\"response\":\"fixture reply\"}");
      capture.putObject("headers").put("authorization", "Bearer alice-secret-1");
      launcher.emitCapture(capture);
      var pwAction = JSON.createObjectNode();
      pwAction.put("type", "input").put("description", "Type into password 'Password'")
        .put("locator", "#password").put("valueLength", 11).put("sensitive", true);
      launcher.emitAction(pwAction);
      var otpAction = JSON.createObjectNode();
      otpAction.put("type", "input").put("description", "Type into textbox 'One-time code'")
        .put("locator", "#otp").put("valueLength", 6).put("sensitive", true);
      launcher.emitAction(otpAction);
      alice.call("POST", "/api/studio/sessions/" + studioSessionId + "/record/stop", null);

      var inferred = alice.call("POST", "/api/studio/sessions/" + studioSessionId + "/infer",
        "{\"promptMarker\":\"probe-marker-1\"}");
      assertEquals("/chat", inferred.path("chatPath").asText());
      var validated = alice.call("POST", "/api/studio/sessions/" + studioSessionId + "/validate",
        "{\"config\":" + inferred + "}");
      assertEquals("CONNECTED", validated.path("state").asText());

      // Provider list is owner-scoped.
      var providers = alice.call("GET", "/api/chat/providers", null);
      assertTrue(providers.toString().contains(aliceConnectionId));
      var bobProviders = bob.call("GET", "/api/chat/providers", null);
      assertFalse(bobProviders.toString().contains(aliceConnectionId));

      // Conversation -> preview -> send over SSE.
      var conv = alice.call("POST", "/api/chat/conversations",
        "{\"connectionId\":\"" + aliceConnectionId + "\",\"title\":\"journey\"}");
      conversationId = conv.path("id").asText();
      var preview = alice.call("POST", "/api/chat/conversations/" + conversationId + "/preview",
        "{\"prompt\":\"say hi\",\"connectionId\":\"" + aliceConnectionId + "\"}");
      var sse = alice.raw("POST", "/api/chat/conversations/" + conversationId + "/send",
        "{\"prompt\":\"say hi\",\"connectionId\":\"" + aliceConnectionId
          + "\",\"confirmationId\":\"" + preview.path("confirmationId").asText() + "\"}");
      assertEquals(200, sse.statusCode());
      String stream = sse.body();
      assertTrue(stream.contains("event:meta"), stream);
      assertTrue(stream.contains("event:delta"), stream);
      assertTrue(stream.contains("fixture reply"), stream);
      assertTrue(stream.contains("event:done"), stream);

      // Replay of a saved capture never touches the browser.
      int browserOpens = launcher.opens.get();
      var store = app.getBean(RecordStore.class);
      var vault = app.getBean(Vault.class);
      var aliceConnRecord = store.get("connections", aliceConnectionId);
      String aliceOwner = aliceConnRecord.path("ownerId").asText();
      vault.putScoped(aliceOwner, aliceConnectionId, "session", "{\"cookies\":[]}");
      var cap = store.parse("{}");
      cap.put("owner", "alice").put("ownerId", aliceOwner)
        .put("connectionId", aliceConnectionId).put("method", "GET")
        .put("url", fixtureBase + "/expired").put("idempotent", true);
      cap.set("headers", JSON.createObjectNode());
      var savedCap = store.save("captured-requests", cap, "alice");
      var replayed = alice.call("POST",
        "/api/discovery/requests/" + savedCap.path("id").asText() + "/replay", "{}");
      assertEquals(200, replayed.path("upstreamStatus").asInt(), replayed.toString());
      assertTrue(replayed.path("refreshedSession").asBoolean());
      assertFalse(replayed.path("browserUsed").asBoolean());
      assertEquals(browserOpens, launcher.opens.get());
      assertEquals(2, expiredHits.get());

      // Fixture that always 401s -> single failed refresh -> RECONNECT_REQUIRED.
      var cap2 = store.parse("{}");
      cap2.put("owner", "alice").put("ownerId", aliceOwner)
        .put("connectionId", aliceConnectionId).put("method", "GET")
        .put("url", fixtureBase + "/always401").put("idempotent", true);
      cap2.set("headers", JSON.createObjectNode());
      var savedCap2 = store.save("captured-requests", cap2, "alice");
      var failed = alice.call("POST",
        "/api/discovery/requests/" + savedCap2.path("id").asText() + "/replay", "{}");
      assertEquals("RECONNECT_REQUIRED", failed.path("error").path("code").asText());
      var reconnected = alice.call("GET", "/api/connections/" + aliceConnectionId, null);
      assertEquals("RECONNECT_REQUIRED", reconnected.path("state").asText());

      // Cross-user isolation.
      assertEquals(404, bob.raw("GET", "/api/chat/conversations/" + conversationId + "/messages", null).statusCode());
      int bobSession = bob.raw("GET", "/api/studio/sessions/" + studioSessionId, null).statusCode();
      assertTrue(bobSession == 403 || bobSession == 404);
      assertEquals(403, bob.raw("POST",
        "/api/discovery/requests/" + savedCap.path("id").asText() + "/replay", "{}").statusCode());
      assertEquals(403, bob.raw("GET", "/api/connections/" + aliceConnectionId, null).statusCode());

      // Studio launch to a non-approved host is blocked.
      assertEquals(400, alice.raw("POST", "/api/studio/sessions",
        "{\"connectionId\":\"" + aliceConnectionId + "\",\"url\":\"http://192.0.2.9:9/\"}").statusCode());

      // Timeline and recorded actions carry no secrets.
      var timeline = alice.call("GET", "/api/studio/timeline?connectionId=" + aliceConnectionId, null);
      String timelineText = timeline.toString();
      assertFalse(timelineText.contains("alice-secret-1"), timelineText);
      var sessionView = alice.call("GET", "/api/studio/sessions/" + studioSessionId, null);
      String actions = sessionView.path("actions").toString();
      assertFalse(actions.contains("alice-secret-1"));
      assertFalse(actions.contains("password-value"), actions);
      assertTrue(actions.contains("valueLength"), actions);
    } finally {
      app.close();
    }

    // Real restart on the same SQLite file: messages persist with provider/account.
    var app2 = startApp(dbFile, profiles);
    try {
      var alice2 = new Client(port(app2));
      alice2.login("alice", "alice-password-1");
      var messages = alice2.call("GET", "/api/chat/conversations/" + conversationId + "/messages", null);
      assertEquals(2, messages.size());
      var assistant = messages.get(1);
      assertEquals("assistant", assistant.path("ROLE").asText(assistant.path("role").asText()));
      String content = String.valueOf(assistant.get("CONTENT") == null
        ? assistant.get("content") : assistant.get("CONTENT"));
      assertTrue(content.contains("fixture reply"), content);
      String meta = String.valueOf(assistant.get("META") == null
        ? assistant.get("meta") : assistant.get("META"));
      assertTrue(meta.contains(aliceConnectionId), meta);
    } finally {
      app2.close();
    }
  }
}
