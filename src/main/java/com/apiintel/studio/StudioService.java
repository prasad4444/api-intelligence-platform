package com.apiintel.studio;

import com.apiintel.activity.ActivityLog;
import com.apiintel.integrations.ApprovedHosts;
import com.apiintel.integrations.ConnectionService;
import com.apiintel.integrations.IntegrationService;
import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Connection Studio backend.
 *
 * <p>Architecture note: provider chat pages cannot be embedded in our web UI
 * (X-Frame-Options / CSP frame-ancestors / cross-origin isolation). Instead
 * the local Spring Boot process launches a controlled headed Chromium window
 * via Playwright with a persistent per-user, per-connection profile under
 * {@code ./data/browser-profiles/{ownerId}/{connectionId}}. The studio UI
 * polls this service for state and streams its activity timeline.
 */
@Service
public class StudioService {

  /** Published by ConnectionService so studio resources are torn down on disconnect. */
  public record ConnectionDisconnectedEvent(String connectionId, String ownerId, String username) {}

  private final RecordStore store;
  private final Vault vault;
  private final ConnectionService connections;
  private final IntegrationService integrations;
  private final ApprovedHosts approved;
  private final ActivityLog activity;
  private final Path profileRoot;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, BrowserLauncher.BrowserHandle> handles = new ConcurrentHashMap<>();
  private final Map<String, Sinks.Many<ObjectNode>> buses = new ConcurrentHashMap<>();
  private BrowserLauncher launcher;

  public StudioService(RecordStore store, Vault vault, ConnectionService connections,
      IntegrationService integrations, ApprovedHosts approved, ActivityLog activity,
      @org.springframework.beans.factory.annotation.Value("${platform.browser-profiles:./data/browser-profiles}") String profileRoot,
      @org.springframework.beans.factory.annotation.Autowired(required = false) BrowserLauncher launcher) {
    this.store = store;
    this.vault = vault;
    this.connections = connections;
    this.integrations = integrations;
    this.approved = approved;
    this.activity = activity;
    this.profileRoot = Path.of(profileRoot);
    this.launcher = launcher == null ? new PlaywrightBrowserLauncher() : launcher;
  }

  public void launcher(BrowserLauncher launcher) {
    this.launcher = launcher;
  }

  public ObjectNode start(ObjectNode input, String username) {
    var connection = connections.getOwned(input.path("connectionId").asText(), username);
    String url = input.path("url").asText(connection.path("baseUrl").asText()).trim();
    if (url.isBlank()) throw new IllegalArgumentException("Enter a studio URL");
    URI uri = URI.create(url);
    approved.enforce(url, connection);
    String ownerId = connection.path("ownerId").asText();
    var session = store.parse("{}");
    session.put("owner", username).put("ownerId", ownerId);
    session.put("connectionId", connection.path("id").asText());
    session.put("studio", true);
    session.put("url", uri.toString());
    session.put("status", "OPENING");
    session.put("startedAt", Instant.now().toString());
    session.putArray("captures");
    session.putArray("actions");
    session = store.save("discovery-sessions", session, username);
    String sessionId = session.path("id").asText();
    buses.put(sessionId, Sinks.many().multicast().onBackpressureBuffer());
    final var open = session;
    executor.submit(() -> openBrowser(open, connection.deepCopy()));
    return open;
  }

  private void openBrowser(ObjectNode session, ObjectNode connection) {
    String sessionId = session.path("id").asText();
    String ownerId = session.path("ownerId").asText();
    String username = session.path("owner").asText();
    try {
      Path profile = profileDir(ownerId, connection.path("id").asText());
      var runtime = connections.runtimeConfig(connection);
      runtime.put("browserProfileDir", profile.toString());
      var handle = launcher.open(new BrowserLauncher.OpenRequest(
        runtime,
        false,
        url -> update(sessionId, username, s -> s.put("currentUrl", url)),
        capture -> onCapture(sessionId, connection.path("id").asText(), ownerId, username, capture),
        action -> onAction(sessionId, username, action),
        () -> cancelled(sessionId)));
      handles.put(sessionId, handle);
      var navigate = handle.evaluate("location.href");
      update(sessionId, username, s -> {
        s.put("status", "WAITING_FOR_SIGN_IN");
        s.put("currentUrl", navigate == null ? s.path("url").asText() : String.valueOf(navigate));
      });
      emit(ownerId, connection.path("id").asText(), sessionId, "BROWSER_OPENED", "Studio browser opened");
      emit(ownerId, connection.path("id").asText(), sessionId, "WAITING_FOR_SIGN_IN", "Complete sign-in in the browser window");
    } catch (Exception e) {
      update(sessionId, username, s -> s.put("status", "ERROR").put("error", e.getMessage() == null ? "Studio failed" : e.getMessage()));
      emit(ownerId, connection.path("id").asText(), sessionId, "ERROR", e.getMessage() == null ? "Studio failed" : e.getMessage());
    }
  }

  private void onCapture(String sessionId, String connectionId, String ownerId, String username, ObjectNode capture) {
    update(sessionId, username, s -> {
      var captures = (com.fasterxml.jackson.databind.node.ArrayNode) s.path("captures");
      if (captures.size() < com.apiintel.integrations.DiscoveryService.MAX_CAPTURES) captures.add(capture);
    });
    emit(ownerId, connectionId, sessionId, "REQUEST_CAPTURED",
      capture.path("method").asText() + " " + capture.path("url").asText());
  }

  private void onAction(String sessionId, String username, ObjectNode action) {
    update(sessionId, username, s -> ((com.fasterxml.jackson.databind.node.ArrayNode) s.path("actions")).add(action));
  }

  public ObjectNode get(String sessionId, String username) {
    return owned(sessionId, username);
  }

  public List<ObjectNode> listForConnection(String connectionId, String username) {
    var connection = connections.getOwned(connectionId, username);
    return store.list("discovery-sessions").stream()
      .filter(s -> s.path("studio").asBoolean(false))
      .filter(s -> connectionId.equals(s.path("connectionId").asText()))
      .filter(s -> connection.path("ownerId").asText().equals(s.path("ownerId").asText()))
      .toList();
  }

  public Flux<ObjectNode> events(String sessionId, String username) {
    owned(sessionId, username);
    var sink = buses.computeIfAbsent(sessionId, id -> Sinks.many().multicast().onBackpressureBuffer());
    return sink.asFlux();
  }

  public List<Map<String, Object>> timeline(String connectionId, String username) {
    var connection = connections.getOwned(connectionId, username);
    return activity.timeline(connection.path("ownerId").asText(), connectionId, null, 500);
  }

  public ObjectNode signedIn(String sessionId, String username) {
    var session = owned(sessionId, username);
    var handle = handles.get(sessionId);
    var connection = connections.getOwned(session.path("connectionId").asText(), username);
    String identity = handle != null && handle.identity() != null ? handle.identity() : "";
    if (identity.isBlank()) {
      identity = connection.path("verifiedIdentity").asText(connection.path("siteHost").asText("user"));
    }
    if (handle != null) {
      String state = handle.storageState();
      if (state != null && !state.isBlank()) {
        vault.putScoped(session.path("ownerId").asText(), connection.path("id").asText(), "session", state);
        connection.put("hasSession", true);
        store.save("connections", connection, username);
      }
    }
    final String verified = identity;
    emit(session.path("ownerId").asText(), connection.path("id").asText(), sessionId,
      "IDENTITY_VERIFIED", "Signed in as " + verified);
    return update(sessionId, username, s -> s.put("status", "READY").put("identity", verified));
  }

  public ObjectNode recordStart(String sessionId, String username, String promptMarker) {
    var session = owned(sessionId, username);
    var handle = handles.get(sessionId);
    if (handle != null) handle.installRecorder(RecorderScript.script(promptMarker));
    session = update(sessionId, username, s -> {
      s.put("status", "RECORDING");
      s.put("promptMarker", promptMarker == null ? "" : promptMarker);
    });
    emit(session.path("ownerId").asText(), session.path("connectionId").asText(), sessionId,
      "RECORDING_STARTED", "Recording requests and actions");
    return session;
  }

  public ObjectNode recordStop(String sessionId, String username) {
    owned(sessionId, username);
    return update(sessionId, username, s -> s.put("status", "REVIEW"));
  }

  public ObjectNode infer(String sessionId, ObjectNode input, String username) {
    var session = owned(sessionId, username);
    String marker = input.path("promptMarker").asText(session.path("promptMarker").asText(""));
    List<ObjectNode> captures = new java.util.ArrayList<>();
    session.path("captures").forEach(c -> captures.add((ObjectNode) c));
    var inferred = ConnectorInference.infer(captures, marker);
    update(sessionId, username, s -> s.set("inferred", inferred));
    emit(session.path("ownerId").asText(), session.path("connectionId").asText(), sessionId,
      inferred.has("error") ? "ERROR" : "CONNECTOR_INFERRED",
      inferred.has("error") ? inferred.path("error").asText() : "Connector draft inferred");
    return inferred;
  }

  public ObjectNode validate(String sessionId, ObjectNode input, String username) {
    var session = owned(sessionId, username);
    var connection = connections.getOwned(session.path("connectionId").asText(), username);
    var config = input.has("config") && input.path("config").isObject()
      ? input.path("config") : session.path("inferred");
    if (!config.isObject()) throw new IllegalArgumentException("Run inference or provide a connector config first");
    for (String field : List.of("chatPath", "aiMethod", "aiResponseFormat", "aiResponsePointer", "replayMode")) {
      if (config.hasNonNull(field)) connection.set(field, config.get(field));
    }
    if (config.hasNonNull("aiRequestTemplate")) connection.set("aiRequestTemplate", config.get("aiRequestTemplate"));
    if (config.hasNonNull("steps")) connection.set("steps", config.get("steps"));
    if (config.hasNonNull("captureIds")) connection.set("captureIds", config.get("captureIds"));
    connection.put("aiChatEnabled", true);
    connection = store.save("connections", connection, username);
    try {
      var runtime = connections.runtimeConfig(connection);
      integrations.testAi(runtime, username);
      connection.put("state", "CONNECTED");
      connection.put("connectorValidated", true);
      connection = store.save("connections", connection, username);
      emit(session.path("ownerId").asText(), connection.path("id").asText(), sessionId,
        "CONNECTOR_VALIDATED", "Connector validated against the provider");
      update(sessionId, username, s -> s.put("status", "VALIDATED"));
    } catch (RuntimeException e) {
      connection.put("connectorValidated", false);
      store.save("connections", connection, username);
      emit(session.path("ownerId").asText(), connection.path("id").asText(), sessionId,
        "ERROR", e.getMessage() == null ? "Validation failed" : e.getMessage());
      throw new IllegalArgumentException("Connector validation failed: " + e.getMessage());
    }
    return connections.redact(connection);
  }

  public ObjectNode cancel(String sessionId, String username) {
    var session = owned(sessionId, username);
    close(sessionId);
    emit(session.path("ownerId").asText(), session.path("connectionId").asText(), sessionId,
      "CANCELLED", "Studio session cancelled");
    return update(sessionId, username, s -> s.put("status", "CANCELLED"));
  }

  @EventListener
  public void onDisconnect(ConnectionDisconnectedEvent event) {
    for (var session : store.list("discovery-sessions")) {
      if (!event.connectionId().equals(session.path("connectionId").asText())) continue;
      if (!event.ownerId().equals(session.path("ownerId").asText())) continue;
      if (!session.path("studio").asBoolean(false)) continue;
      close(session.path("id").asText());
      session.put("status", "CANCELLED").put("cancelReason", "Connection disconnected");
      store.save("discovery-sessions", session, event.username());
    }
    Path profile = profileRoot.resolve(event.ownerId()).resolve(event.connectionId());
    deleteRecursively(profile);
    emit(event.ownerId(), event.connectionId(), null, "DISCONNECTED", "Connection disconnected");
  }

  public void cancelInFlight(String connectionId, String ownerId) {
    // Called on disconnect: close any studio browser sessions for the connection.
    for (var session : store.list("discovery-sessions")) {
      if (connectionId.equals(session.path("connectionId").asText())
          && ownerId.equals(session.path("ownerId").asText())
          && session.path("studio").asBoolean(false)) {
        close(session.path("id").asText());
      }
    }
  }

  Path profileDir(String ownerId, String connectionId) {
    Path dir = profileRoot.resolve(ownerId).resolve(connectionId);
    try {
      Files.createDirectories(dir);
      try {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
      } catch (UnsupportedOperationException ignored) {
        // Windows ACLs already scope the directory to the current user.
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot create browser profile directory");
    }
    return dir;
  }

  private ObjectNode owned(String sessionId, String username) {
    var session = store.get("discovery-sessions", sessionId);
    if (!username.equals(session.path("owner").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Studio session belongs to another user");
    }
    return session;
  }

  private boolean cancelled(String sessionId) {
    try {
      var session = store.get("discovery-sessions", sessionId);
      return "CANCELLED".equals(session.path("status").asText());
    } catch (RuntimeException e) {
      return true;
    }
  }

  private ObjectNode update(String sessionId, String username, java.util.function.Consumer<ObjectNode> mutate) {
    var session = store.get("discovery-sessions", sessionId);
    mutate.accept(session);
    var saved = store.save("discovery-sessions", session, username);
    var bus = buses.get(sessionId);
    if (bus != null) {
      var state = store.parse("{}");
      state.put("type", "state");
      state.set("session", saved);
      bus.tryEmitNext(state);
    }
    return saved;
  }

  private void emit(String ownerId, String connectionId, String sessionId, String type, String message) {
    activity.emit(ownerId, connectionId, sessionId, type, message);
    if (sessionId != null) {
      var bus = buses.get(sessionId);
      if (bus != null) {
        var event = store.parse("{}");
        event.put("type", type).put("message", ActivityLog.redact(message));
        bus.tryEmitNext(event);
      }
    }
  }

  private void close(String sessionId) {
    var handle = handles.remove(sessionId);
    if (handle != null) {
      try {
        handle.close();
      } catch (Exception ignored) {
      }
    }
  }

  private void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
      });
    } catch (IOException ignored) {
    }
  }
}
