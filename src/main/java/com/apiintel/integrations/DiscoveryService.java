package com.apiintel.integrations;

import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class DiscoveryService {

  public static final int MAX_CAPTURES = 200;
  public static final int MAX_BODY = 200_000;

  private final RecordStore store;
  private final Vault vault;
  private final ConnectionService connections;
  private final ApprovedHosts approved;
  private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
  private CaptureTransport transport = new PlaywrightCaptureTransport();

  public DiscoveryService(
    RecordStore store,
    Vault vault,
    ConnectionService connections,
    ApprovedHosts approved
  ) {
    this.store = store;
    this.vault = vault;
    this.connections = connections;
    this.approved = approved;
  }

  public void transport(CaptureTransport transport) {
    this.transport = transport;
  }

  public ObjectNode start(ObjectNode input, String username) {
    String connectionId = input.path("connectionId").asText();
    var connection = connections.getOwned(connectionId, username);
    String targetUrl = input.path("url").asText().trim();
    if (targetUrl.isBlank()) throw new IllegalArgumentException("Enter a discovery URL");
    URI uri = approveNavigation(targetUrl, connection);
    boolean interactive = input.path("interactive").asBoolean(false);
    var session = store.parse("{}");
    session.put("owner", username);
    session.put("ownerId", connection.path("ownerId").asText());
    session.put("connectionId", connectionId);
    session.put("connectionRevision", connection.path("revision").asInt());
    session.put("url", uri.toString());
    session.put("interactive", interactive);
    session.put("status", "RUNNING");
    session.put("startedAt", Instant.now().toString());
    session.putArray("captures");
    session = store.save("discovery-sessions", session, username);
    final String sessionId = session.path("id").asText();
    cancelled.put(sessionId, new AtomicBoolean(false));
    try {
      var result = transport.capture(
        uri.toString(),
        interactive,
        () -> cancelled.getOrDefault(sessionId, new AtomicBoolean()).get(),
        this::approveSubrequest
      );
      ArrayNode captures = session.putArray("captures");
      int count = 0;
      for (ObjectNode capture : result.captures()) {
        if (count++ >= MAX_CAPTURES) break;
        captures.add(redactCapture(capture));
      }
      if (result.sessionState() != null && !result.sessionState().isBlank()) {
        vault.putScoped(
          connection.path("ownerId").asText(),
          connectionId,
          "session",
          result.sessionState()
        );
        connection.put("hasSession", true);
        store.save("connections", connection, username);
      }
      session.put("status", "COMPLETED");
      session.put("completedAt", Instant.now().toString());
      session.put("browserUsed", result.browserUsed());
    } catch (DiscoveryReconnectException e) {
      session.put("status", "RECONNECT_REQUIRED");
      session.put("error", e.getMessage());
      connections.markReconnect(connectionId, username, e.getMessage());
    } catch (Exception e) {
      session.put("status", "ERROR");
      session.put("error", e.getMessage() == null ? "Discovery failed" : e.getMessage());
    } finally {
      cancelled.remove(sessionId);
    }
    return store.save("discovery-sessions", session, username);
  }

  public ObjectNode cancel(String sessionId, String username) {
    var session = store.get("discovery-sessions", sessionId);
    if (!username.equals(session.path("owner").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Discovery session belongs to another user");
    }
    cancelled.computeIfAbsent(sessionId, id -> new AtomicBoolean()).set(true);
    session.put("status", "CANCELLED");
    return store.save("discovery-sessions", session, username);
  }

  public ObjectNode saveSelected(String sessionId, ObjectNode input, String username) {
    var session = store.get("discovery-sessions", sessionId);
    if (!username.equals(session.path("owner").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Discovery session belongs to another user");
    }
    var connection = connections.getOwned(session.path("connectionId").asText(), username);
    var selected = input.path("indexes");
    if (!selected.isArray() || selected.isEmpty()) throw new IllegalArgumentException("Select at least one captured request");
    var saved = store.parse("{}").putArray("saved");
    int i = 0;
    for (JsonNode indexNode : selected) {
      int index = indexNode.asInt(-1);
      var captures = session.path("captures");
      if (index < 0 || index >= captures.size()) throw new IllegalArgumentException("Invalid capture index");
      var capture = (ObjectNode) captures.get(index);
      var record = capture.deepCopy();
      record.put("owner", username);
      record.put("ownerId", connection.path("ownerId").asText());
      record.put("connectionId", connection.path("id").asText());
      record.put("connectionRevision", connection.path("revision").asInt());
      record.put("discoverySessionId", sessionId);
      record.put("idempotent", Set.of("GET", "HEAD", "OPTIONS").contains(record.path("method").asText()));
      if (capture.hasNonNull("sensitiveBody")) {
        vault.putScoped(
          connection.path("ownerId").asText(),
          connection.path("id").asText(),
          "capture-body-" + UUID.randomUUID(),
          capture.path("sensitiveBody").asText()
        );
        record.put("bodyStored", true);
        record.remove("sensitiveBody");
      }
      if (capture.hasNonNull("cookieHeader")) {
        vault.putScoped(
          connection.path("ownerId").asText(),
          connection.path("id").asText(),
          "capture-cookie-" + (i++),
          capture.path("cookieHeader").asText()
        );
        record.put("hasCookies", true);
        record.remove("cookieHeader");
      }
      saved.add(store.save("captured-requests", record, username));
    }
    var result = store.parse("{}");
    result.set("saved", saved);
    return result;
  }

  public List<ObjectNode> listSaved(String username) {
    return store.list("captured-requests").stream()
      .filter(r -> username.equals(r.path("owner").asText()))
      .map(r -> {
        var view = r.deepCopy();
        view.remove(List.of("sensitiveBody", "cookieHeader", "setCookie"));
        return view;
      })
      .toList();
  }

  public ObjectNode getSaved(String id, String username) {
    var record = store.get("captured-requests", id);
    if (!username.equals(record.path("owner").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Captured request belongs to another user");
    }
    return record;
  }

  URI approveNavigation(String url, ObjectNode connection) {
    approved.enforce(url, connection);
    return URI.create(url);
  }

  void approveSubrequest(String url) {
    URI uri = URI.create(url);
    if (uri.getHost() == null) throw new IllegalArgumentException("Invalid discovery subrequest");
    if (!approved.allows(uri.getHost())) {
      throw new IllegalArgumentException("Blocked discovery destination: " + uri.getHost());
    }
  }

  private ObjectNode redactCapture(ObjectNode capture) {
    var view = capture.deepCopy();
    if (view.has("headers") && view.path("headers").isObject()) {
      var headers = (ObjectNode) view.path("headers");
      for (String name : List.of("authorization", "cookie", "set-cookie", "x-api-key")) {
        Iterator<String> names = headers.fieldNames();
        while (names.hasNext()) {
          String field = names.next();
          if (field.equalsIgnoreCase(name)) headers.put(field, "REDACTED");
        }
      }
    }
    String body = view.path("body").asText("");
    if (body.length() > 4000) {
      view.put("bodyPreview", body.substring(0, 4000) + "…");
      view.put("sensitiveBody", body);
      view.put("truncated", true);
      view.remove("body");
    } else if (!body.isBlank()) {
      view.put("bodyPreview", body);
      view.put("sensitiveBody", body);
      view.remove("body");
    }
    if (view.hasNonNull("cookieHeader")) {
      view.put("cookiePreview", "REDACTED");
    }
    return view;
  }

  public record CaptureResult(List<ObjectNode> captures, String sessionState, boolean browserUsed) {}

  public interface CaptureTransport {
    CaptureResult capture(String url, boolean interactive, java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<String> approveSubrequest) throws Exception;
  }

  public static class DiscoveryReconnectException extends RuntimeException {
    public DiscoveryReconnectException(String message) {
      super(message);
    }
  }
}
