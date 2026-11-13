package com.apiintel.integrations;

import com.apiintel.diagnostics.ApiRequestLog;
import com.apiintel.network.NetworkGuard;
import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReplayService {

  private final RecordStore store;
  private final Vault vault;
  private final ConnectionService connections;
  private final NetworkGuard guard;
  private final ObjectMapper json = new ObjectMapper();
  private final Set<String> hosts;
  private final Set<String> privateHosts;
  private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();
  private final ThreadLocal<Boolean> browserStarted = ThreadLocal.withInitial(() -> false);

  public ReplayService(
    RecordStore store,
    Vault vault,
    ConnectionService connections,
    NetworkGuard guard,
    @Value("${platform.integration-hosts}") String hosts,
    @Value("${platform.integration-private-hosts:}") String privateHosts
  ) {
    this.store = store;
    this.vault = vault;
    this.connections = connections;
    this.guard = guard;
    this.hosts = split(hosts);
    this.privateHosts = split(privateHosts);
  }

  public boolean browserStartedDuringReplay() {
    return Boolean.TRUE.equals(browserStarted.get());
  }

  public ObjectNode replay(String captureId, ObjectNode input, String username) {
    browserStarted.set(false);
    var saved = store.get("captured-requests", captureId);
    if (!username.equals(saved.path("owner").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Captured request belongs to another user");
    }
    var connection = connections.getOwned(saved.path("connectionId").asText(), username);
    if (Set.of("NOT_CONNECTED", "RECONNECT_REQUIRED").contains(connection.path("state").asText())) {
      return errorResult(saved, connection, "RECONNECT_REQUIRED", "Reconnect required before replay");
    }
    String method = saved.path("method").asText("GET");
    boolean force = input.path("confirmNonIdempotent").asBoolean(false);
    if (!saved.path("idempotent").asBoolean(Set.of("GET", "HEAD", "OPTIONS").contains(method)) && !force) {
      return errorResult(saved, connection, "NON_IDEMPOTENT", "Confirm replay of a non-idempotent request");
    }
    URI uri = URI.create(saved.path("url").asText());
    enforceDestination(uri, connection);
    try {
      return execute(saved, connection, username, false);
    } catch (SessionExpiredException e) {
      synchronized (refreshLocks.computeIfAbsent(connection.path("id").asText(), id -> new Object())) {
        try {
          refreshSession(connection, username);
          return execute(saved, connection, username, true);
        } catch (SessionExpiredException | IllegalArgumentException refreshError) {
          connections.markReconnect(connection.path("id").asText(), username, refreshError.getMessage());
          return errorResult(saved, connection, "RECONNECT_REQUIRED", refreshError.getMessage());
        }
      }
    }
  }

  private ObjectNode execute(ObjectNode saved, ObjectNode connection, String username, boolean afterRefresh) {
    try {
      URI uri = URI.create(saved.path("url").asText());
      var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
      String method = saved.path("method").asText("GET");
      String body = body(saved, connection);
      HttpRequest.BodyPublisher publisher = body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body);
      builder.method(method, publisher);
      applyHeaders(builder, saved, connection);
      HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
      HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      updateCookies(connection, response, username);
      int status = response.statusCode();
      if (status == 401 || isExpiry(response)) {
        throw new SessionExpiredException("Provider session expired");
      }
      if (status == 403) {
        return errorResult(saved, connection, "FORBIDDEN", "Insufficient permissions for this destination");
      }
      if (status == 429) {
        return errorResult(saved, connection, "RATE_LIMITED", "Provider rate limit reached");
      }
      return success(saved, connection, response, afterRefresh);
    } catch (SessionExpiredException e) {
      throw e;
    } catch (Exception e) {
      return errorResult(saved, connection, "ERROR", e.getMessage() == null ? "Replay failed" : e.getMessage());
    }
  }

  private ObjectNode success(ObjectNode saved, ObjectNode connection, HttpResponse<String> response, boolean refreshed) throws Exception {
    String raw = response.body() == null ? "" : response.body();
    boolean truncated = raw.length() > DiscoveryService.MAX_BODY;
    if (truncated) raw = raw.substring(0, DiscoveryService.MAX_BODY);
    ObjectNode result = json.createObjectNode();
    result.put("connectorId", connection.path("id").asText());
    result.put("requestId", saved.path("id").asText());
    result.put("platformRequestId", ApiRequestLog.requestId());
    result.put("upstreamStatus", response.statusCode());
    result.put("contentType", response.headers().firstValue("content-type").orElse(""));
    result.put("truncated", truncated);
    result.put("refreshedSession", refreshed);
    result.put("browserUsed", browserStartedDuringReplay());
    String contentType = result.path("contentType").asText();
    if (contentType.contains("application/json") || raw.trim().startsWith("{") || raw.trim().startsWith("[")) {
      try {
        result.set("data", json.readTree(raw.isBlank() ? "null" : raw));
      } catch (Exception e) {
        result.put("dataText", raw);
        result.putObject("error").put("code", "NON_JSON").put("message", "Response was not valid JSON");
      }
    } else if (contentType.contains("text/event-stream") || raw.startsWith("data:")) {
      result.set("data", parseSse(raw));
    } else if (raw.isBlank()) {
      result.putNull("data");
    } else {
      result.put("dataText", raw);
    }
    store.audit(saved.path("owner").asText(), "connection:replay", saved.path("id").asText());
    return result;
  }

  private ObjectNode parseSse(String raw) {
    var array = json.createArrayNode();
    for (String line : raw.split("\n")) {
      if (line.startsWith("data:")) {
        String payload = line.substring(5).trim();
        if (payload.equals("[DONE]")) continue;
        try {
          array.add(json.readTree(payload));
        } catch (Exception e) {
          array.add(payload);
        }
      }
    }
    return json.createObjectNode().set("events", array);
  }

  private void applyHeaders(HttpRequest.Builder builder, ObjectNode saved, ObjectNode connection) {
    JsonNode headers = saved.path("headers");
    if (headers.isObject()) {
      headers.fields().forEachRemaining(entry -> {
        String name = entry.getKey();
        if (name.equalsIgnoreCase("content-length") || name.equalsIgnoreCase("host") || name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("cookie")) {
          return;
        }
        if (Set.of("user-agent", "accept", "referer", "origin", "content-type", "accept-language").contains(name.toLowerCase(Locale.ROOT))
            || name.toLowerCase(Locale.ROOT).startsWith("x-")) {
          builder.header(name, entry.getValue().asText());
        }
      });
    }
    String credential = vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "credential");
    if (!credential.isBlank()) {
      String type = connection.path("authType").asText("BEARER");
      switch (type) {
        case "AUTHORIZATION" -> builder.header("Authorization", credential.trim());
        case "BEARER" -> builder.header("Authorization", "Bearer " + credential.trim().replaceFirst("(?i)^Bearer +", ""));
        case "API_KEY" -> builder.header(connection.path("headerName").asText("X-API-Key"), credential);
        default -> {
        }
      }
    }
    String cookies = cookieHeader(connection, URI.create(saved.path("url").asText()));
    if (!cookies.isBlank()) builder.header("Cookie", cookies);
  }

  private String body(ObjectNode saved, ObjectNode connection) {
    if (saved.path("bodyStored").asBoolean()) {
      for (String name : vault.scopedNames(connection.path("ownerId").asText(), connection.path("id").asText())) {
        if (name.startsWith("capture-body-")) return vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), name);
      }
    }
    return saved.path("bodyPreview").asText(null);
  }

  private String cookieHeader(ObjectNode connection, URI uri) {
    String session = vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "session");
    if (session.isBlank()) {
      for (String name : vault.scopedNames(connection.path("ownerId").asText(), connection.path("id").asText())) {
        if (name.startsWith("capture-cookie-")) {
          return vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), name);
        }
      }
      return "";
    }
    try {
      JsonNode root = json.readTree(session);
      List<String> pairs = new ArrayList<>();
      for (JsonNode cookie : root.path("cookies")) {
        if (!cookieAllowed(cookie, uri)) continue;
        pairs.add(cookie.path("name").asText() + "=" + cookie.path("value").asText());
      }
      return String.join("; ", pairs);
    } catch (Exception e) {
      return "";
    }
  }

  private boolean cookieAllowed(JsonNode cookie, URI uri) {
    String domain = cookie.path("domain").asText("");
    String path = cookie.path("path").asText("/");
    boolean secure = cookie.path("secure").asBoolean(false);
    if (secure && !"https".equals(uri.getScheme())) return false;
    if (!domain.isBlank()) {
      String host = uri.getHost() == null ? "" : uri.getHost();
      String normalized = domain.startsWith(".") ? domain.substring(1) : domain;
      if (!host.equalsIgnoreCase(normalized) && !host.toLowerCase(Locale.ROOT).endsWith("." + normalized.toLowerCase(Locale.ROOT))) {
        return false;
      }
    }
    String uriPath = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
    return uriPath.startsWith(path.isBlank() ? "/" : path);
  }

  private void updateCookies(ObjectNode connection, HttpResponse<String> response, String username) {
    List<String> setCookies = response.headers().allValues("set-cookie");
    if (setCookies.isEmpty()) return;
    try {
      String ownerId = connection.path("ownerId").asText();
      String connectionId = connection.path("id").asText();
      String session = vault.getScoped(ownerId, connectionId, "session");
      ObjectNode root = session.isBlank() ? json.createObjectNode() : (ObjectNode) json.readTree(session);
      var cookies = root.withArray("cookies");
      for (String header : setCookies) {
        String[] parts = header.split(";", 2);
        String[] nv = parts[0].split("=", 2);
        if (nv.length < 2) continue;
        ObjectNode cookie = json.createObjectNode();
        cookie.put("name", nv[0].trim());
        cookie.put("value", nv[1].trim());
        cookie.put("path", "/");
        cookie.put("domain", URI.create(connection.path("baseUrl").asText()).getHost());
        cookies.add(cookie);
      }
      vault.putScoped(ownerId, connectionId, "session", root.toString());
      connection.put("hasSession", true);
      store.save("connections", connection, username);
    } catch (Exception ignored) {
    }
  }

  private void refreshSession(ObjectNode connection, String username) {
    String refresh = vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "refresh_token");
    if (!refresh.isBlank() && connection.path("tokenPath").asText().length() > 0) {
      // OAuth refresh is best-effort using stored refresh token against the approved host.
      try {
        URI tokenUri = URI.create(connection.path("baseUrl").asText().replaceAll("/$", "") + connection.path("tokenPath").asText());
        enforceDestination(tokenUri, connection);
        String form = "grant_type=refresh_token&refresh_token=" +
          java.net.URLEncoder.encode(refresh, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
          .timeout(Duration.ofSeconds(20))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new SessionExpiredException("Reconnect required");
        JsonNode body = json.readTree(response.body());
        if (!body.hasNonNull("access_token")) throw new SessionExpiredException("Reconnect required");
        vault.putScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "credential",
          "Bearer " + body.path("access_token").asText());
        if (body.hasNonNull("refresh_token")) {
          vault.putScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "refresh_token",
            body.path("refresh_token").asText());
        }
        connection.put("state", "CONNECTED");
        store.save("connections", connection, username);
        return;
      } catch (SessionExpiredException e) {
        throw e;
      } catch (Exception e) {
        throw new SessionExpiredException("Reconnect required");
      }
    }
    String session = vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "session");
    if (session.isBlank()) throw new SessionExpiredException("Reconnect required");
    // Saved browser session present: retry once without launching Chromium.
    connection.put("state", "CONNECTED");
    store.save("connections", connection, username);
  }

  private void enforceDestination(URI uri, ObjectNode connection) {
    if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("Invalid replay URL");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    String site = connection.path("siteHost").asText("").toLowerCase(Locale.ROOT);
    String originHost = URI.create(savedBase(connection)).getHost();
    if (originHost != null && !originHost.equalsIgnoreCase(host) && !host.equals(site)
        && !hosts.contains(host) && !privateHosts.contains(host)) {
      throw new IllegalArgumentException("Blocked replay destination");
    }
    if (!hosts.contains(host) && !privateHosts.contains(host) && !host.equals(site)) {
      guard.validate(uri.toString());
    }
  }

  private String savedBase(ObjectNode connection) {
    return connection.path("baseUrl").asText("https://example.invalid");
  }

  private boolean isExpiry(HttpResponse<String> response) {
    String www = response.headers().firstValue("www-authenticate").orElse("").toLowerCase(Locale.ROOT);
    return www.contains("invalid_token") || www.contains("expired");
  }

  private ObjectNode errorResult(ObjectNode saved, ObjectNode connection, String code, String message) {
    ObjectNode result = json.createObjectNode();
    result.put("connectorId", connection.path("id").asText());
    result.put("requestId", saved.path("id").asText());
    result.put("platformRequestId", ApiRequestLog.requestId());
    result.put("upstreamStatus", 0);
    result.put("contentType", "");
    result.put("truncated", false);
    result.put("browserUsed", browserStartedDuringReplay());
    result.putNull("data");
    result.putObject("error").put("code", code).put("message", message);
    return result;
  }

  private Set<String> split(String value) {
    var out = new HashSet<String>();
    if (value == null) return out;
    for (String part : value.split(",")) if (!part.isBlank()) out.add(part.trim().toLowerCase(Locale.ROOT));
    return out;
  }

  static class SessionExpiredException extends RuntimeException {
    SessionExpiredException(String message) {
      super(message);
    }
  }
}
