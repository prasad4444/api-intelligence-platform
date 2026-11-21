package com.apiintel.splunk;

import com.apiintel.activity.ActivityLog;
import com.apiintel.integrations.ApprovedHosts;
import com.apiintel.security.Vault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Low-level Splunk management-port HTTP client. Every outbound request is
 * enforced through {@link ApprovedHosts}; credentials live in the scoped
 * {@link Vault} and never appear in logs or exceptions.
 *
 * <p>Session auth re-login on 401 uses the same single-flight pattern as
 * {@code TokenRefresher} (one in-flight login per connection) — it cannot call
 * TokenRefresher itself because that service only handles OAuth refresh and
 * browser-session reuse, not the Splunk {@code /services/auth/login} flow.
 */
@Service
public class SplunkClient {

  /** Structured Splunk-facing error surfaced to API responses. */
  public static class SplunkException extends RuntimeException {
    public final int status;
    public final String code;
    public SplunkException(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  private final Vault vault;
  private final ApprovedHosts approved;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10)).build();
  private final ConcurrentHashMap<String, CompletableFuture<String>> loginInflight = new ConcurrentHashMap<>();

  public SplunkClient(Vault vault, ApprovedHosts approved) {
    this.vault = vault;
    this.approved = approved;
  }

  /** Semantic version check: components compared numerically, missing = 0. */
  public static boolean atLeast(String version, String floor) {
    String[] a = version.split("[.\\-]");
    String[] b = floor.split("[.\\-]");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int av = i < a.length ? parseInt(a[i]) : 0;
      int bv = i < b.length ? parseInt(b[i]) : 0;
      if (av != bv) return av > bv;
    }
    return true;
  }

  private static int parseInt(String s) {
    try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (Exception e) { return 0; }
  }

  public String get(String path, ObjectNode connection, Map<String, String> query) {
    return request("GET", path, query, null, connection, true).body;
  }

  public String postForm(String path, ObjectNode connection, Map<String, String> form) {
    return request("POST", path, null, form, connection, true).body;
  }

  /** Auth login (form) → sessionKey, cached in the scoped vault. */
  public String login(ObjectNode connection) {
    String ownerId = connection.path("ownerId").asText();
    String connectionId = connection.path("id").asText();
    CompletableFuture<String> mine = new CompletableFuture<>();
    CompletableFuture<String> existing;
    synchronized (loginInflight) {
      existing = loginInflight.putIfAbsent(connectionId, mine);
    }
    if (existing != null) return existing.join();
    try {
      String key = doLogin(connection);
      mine.complete(key);
      return key;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      loginInflight.remove(connectionId, mine);
    }
  }

  private String doLogin(ObjectNode connection) {
    String ownerId = connection.path("ownerId").asText();
    String connectionId = connection.path("id").asText();
    String creds = vault.getScoped(ownerId, connectionId, "credential");
    if (creds.isBlank() || !creds.contains(":")) {
      throw new SplunkException(401, "SPLUNK_AUTH", "Splunk session auth needs a stored username:password credential");
    }
    String[] parts = creds.split(":", 2);
    Map<String, String> form = new LinkedHashMap<>();
    form.put("username", parts[0]);
    form.put("password", parts[1]);
    form.put("output_mode", "json");
    RawResponse res = request("POST", "/services/auth/login", null, form, connection, false);
    if (res.status == 401 || res.status == 403) {
      throw new SplunkException(res.status, "SPLUNK_AUTH", "Splunk login rejected (reconnect required)");
    }
    if (res.status >= 400) {
      throw new SplunkException(res.status, "SPLUNK_ERROR", "Splunk login failed: HTTP " + res.status);
    }
    try {
      String key = json.readTree(res.body).path("sessionKey").asText("");
      if (key.isBlank()) throw new SplunkException(502, "SPLUNK_ERROR", "Splunk login returned no sessionKey");
      vault.putScoped(ownerId, connectionId, "session", key);
      return key;
    } catch (SplunkException e) {
      throw e;
    } catch (Exception e) {
      throw new SplunkException(502, "SPLUNK_ERROR", "Splunk login response unreadable");
    }
  }

  public record RawResponse(int status, String body) {}

  /**
   * One HTTP call. On 401 with SESSION auth: re-login once (single-flight) and
   * retry once; a second 401 surfaces as SPLUNK_AUTH so callers can mark the
   * connection RECONNECT_REQUIRED.
   */
  public RawResponse request(String method, String path, Map<String, String> query,
                             Map<String, String> form, ObjectNode connection, boolean retryAuth) {
    String url = buildUrl(connection, path, query);
    approved.enforce(url, connection);
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
    boolean loggedIn = applyAuth(b, connection, retryAuth);
    if ("GET".equals(method)) b.GET();
    else if (form != null) {
      b.header("Content-Type", "application/x-www-form-urlencoded");
      b.POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)));
    } else b.POST(HttpRequest.BodyPublishers.noBody());
    RawResponse res = send(b.build());
    if (res.status == 401 && retryAuth && "SESSION".equals(connection.path("authType").asText())) {
      if (!loggedIn) {
        // stale cached session: drop it and re-login once (single-flight)
        String ownerId = connection.path("ownerId").asText();
        vault.deleteScoped(ownerId, connection.path("id").asText(), "session");
        login(connection);
      }
      // when this chain already logged in, a fresh login would yield the same
      // rejected key — retry once as-is, a second 401 surfaces SPLUNK_AUTH
      return request(method, path, query, form, connection, false);
    }
    if (res.status == 401) {
      throw new SplunkException(401, "SPLUNK_AUTH", "Splunk authentication failed (reconnect required)");
    }
    if (res.status == 403) {
      throw new SplunkException(403, "SPLUNK_PERMISSION",
          "Splunk permission denied: " + extractMessage(res.body));
    }
    if (res.status == 503) {
      throw new SplunkException(503, "SPLUNK_QUOTA",
          "Splunk quota exceeded: " + extractMessage(res.body));
    }
    return res;
  }

  /** Applies auth headers; returns true when a session login was performed. */
  private boolean applyAuth(HttpRequest.Builder b, ObjectNode connection, boolean mayLogin) {
    String ownerId = connection.path("ownerId").asText();
    String connectionId = connection.path("id").asText();
    switch (connection.path("authType").asText("SPLUNK_TOKEN")) {
      case "SPLUNK_TOKEN" -> {
        String token = vault.getScoped(ownerId, connectionId, "credential");
        if (!token.isBlank()) b.header("Authorization", "Bearer " + token);
      }
      case "BASIC" -> {
        String creds = vault.getScoped(ownerId, connectionId, "credential");
        if (!creds.isBlank()) {
          b.header("Authorization", "Basic " + java.util.Base64.getEncoder()
              .encodeToString(creds.getBytes(StandardCharsets.UTF_8)));
        }
      }
      case "SESSION" -> {
        String key = vault.getScoped(ownerId, connectionId, "session");
        if (key.isBlank() && mayLogin) {
          key = login(connection);
          if (!key.isBlank()) {
            b.header("Authorization", "Splunk " + key);
            return true;
          }
        }
        if (!key.isBlank()) b.header("Authorization", "Splunk " + key);
      }
      default -> { /* NONE */ }
    }
    return false;
  }

  private RawResponse send(HttpRequest request) {
    try {
      HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
      return new RawResponse(res.statusCode(), res.body() == null ? "" : res.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SplunkException(0, "SPLUNK_ERROR", "Splunk request interrupted");
    } catch (Exception e) {
      throw new SplunkException(0, "SPLUNK_ERROR", "Splunk request failed: " + e.getClass().getSimpleName());
    }
  }

  private String buildUrl(ObjectNode connection, String path, Map<String, String> query) {
    String base = connection.path("baseUrl").asText().replaceAll("/+$", "");
    StringBuilder url = new StringBuilder(base).append(path);
    if (query != null && !query.isEmpty()) {
      url.append("?");
      query.forEach((k, v) -> url.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
          .append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append("&"));
      url.setLength(url.length() - 1);
    }
    return url.toString();
  }

  private String encodeForm(Map<String, String> form) {
    StringBuilder out = new StringBuilder();
    form.forEach((k, v) -> out.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
        .append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append("&"));
    return out.toString();
  }

  String extractMessage(String body) {
    try {
      JsonNode node = json.readTree(body);
      String msg = node.path("messages").isArray() && !node.path("messages").isEmpty()
          ? node.path("messages").get(0).path("text").asText("") : "";
      if (!msg.isBlank()) return ActivityLog.redact(msg);
      return ActivityLog.redact(node.toString()).substring(0, Math.min(300, body.length()));
    } catch (Exception e) {
      return ActivityLog.redact(body == null ? "" : body.substring(0, Math.min(300, body.length())));
    }
  }

  public String namespace(ObjectNode connection) {
    String owner = connection.path("splunkOwner").asText("-");
    String app = connection.path("splunkApp").asText("search");
    return "/servicesNS/" + owner + "/" + app;
  }
}
