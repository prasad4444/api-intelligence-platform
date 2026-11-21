package com.apiintel.oauth;

import com.apiintel.activity.ActivityLog;
import com.apiintel.integrations.ApprovedHosts;
import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Coordinated credential/session refresh for personal connections. Concurrent
 * callers share a single in-flight refresh per connection (single-flight) so
 * rotating refresh tokens (e.g. Atlassian) are used exactly once. On failure
 * the caller marks the connection RECONNECT_REQUIRED and emits
 * USER_ACTION_REQUIRED.
 */
@Service
public class TokenRefresher {

  public static class RefreshException extends RuntimeException {
    public RefreshException(String message) {
      super(message);
    }
  }

  private final RecordStore store;
  private final Vault vault;
  private final ApprovedHosts approved;
  private ActivityLog activity;
  private final ObjectMapper json = new ObjectMapper();
  private final ConcurrentHashMap<String, CompletableFuture<Boolean>> inflight = new ConcurrentHashMap<>();

  public TokenRefresher(RecordStore store, Vault vault, ApprovedHosts approved) {
    this.store = store;
    this.vault = vault;
    this.approved = approved;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setActivity(ActivityLog activity) {
    this.activity = activity;
  }

  /** True when a usable OAuth refresh token or stored session exists. */
  public boolean canRefresh(ObjectNode connection) {
    String refresh = vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "refresh_token");
    String session = vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "session");
    return !refresh.isBlank() || !session.isBlank();
  }

  /**
   * Refresh once per connection across concurrent callers. Returns true when
   * the credential was renewed; throws RefreshException when re-auth is needed.
   */
  public boolean refresh(ObjectNode connection, String username) {
    String connectionId = connection.path("id").asText();
    CompletableFuture<Boolean> mine = new CompletableFuture<>();
    CompletableFuture<Boolean> existing;
    synchronized (inflight) {
      existing = inflight.putIfAbsent(connectionId, mine);
    }
    if (existing != null) {
      try {
        return existing.join();
      } catch (java.util.concurrent.CompletionException e) {
        if (e.getCause() instanceof RefreshException re) throw re;
        throw new RefreshException(e.getCause() == null ? "Reconnect required" : e.getCause().getMessage());
      }
    }
    try {
      boolean result = doRefresh(connection, username);
      mine.complete(result);
      return result;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      if (e instanceof RefreshException re) throw re;
      throw new RefreshException(e.getMessage() == null ? "Reconnect required" : e.getMessage());
    } finally {
      inflight.remove(connectionId, mine);
    }
  }

  public int inflightCount() {
    return inflight.size();
  }

  private boolean doRefresh(ObjectNode connection, String username) {
    String ownerId = connection.path("ownerId").asText();
    String connectionId = connection.path("id").asText();
    emit(ownerId, connectionId, "REFRESH_ATTEMPTED", "Refreshing stored credentials");
    String refresh = vault.getScoped(ownerId, connectionId, "refresh_token");
    String tokenUrl = tokenUrl(connection);
    if (!refresh.isBlank() && !tokenUrl.isBlank()) {
      try {
        URI uri = URI.create(tokenUrl);
        approved.enforce(tokenUrl, connection);
        var form = new StringBuilder("grant_type=refresh_token&refresh_token=")
          .append(java.net.URLEncoder.encode(refresh, StandardCharsets.UTF_8));
        String clientId = connection.path("clientId").asText("");
        if (!clientId.isBlank()) {
          form.append("&client_id=").append(java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        }
        String clientSecret = vault.getScoped(ownerId, connectionId, "client_secret");
        if (!clientSecret.isBlank()) {
          form.append("&client_secret=").append(java.net.URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(20))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
          .build();
        HttpResponse<String> response = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build()
          .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new RefreshException("Reconnect required");
        JsonNode body = json.readTree(response.body());
        if (!body.hasNonNull("access_token")) throw new RefreshException("Reconnect required");
        vault.putScoped(ownerId, connectionId, "credential", "Bearer " + body.path("access_token").asText());
        // Atlassian rotates refresh tokens — always persist the new one.
        if (body.hasNonNull("refresh_token")) {
          vault.putScoped(ownerId, connectionId, "refresh_token", body.path("refresh_token").asText());
        }
        if (body.hasNonNull("expires_in")) {
          vault.putScoped(ownerId, connectionId, "token_expiry",
            Instant.now().plusSeconds(body.path("expires_in").asLong()).toString());
        }
        connection.put("state", "CONNECTED");
        store.save("connections", connection, username);
        emit(ownerId, connectionId, "REFRESH_SUCCEEDED", "Credentials refreshed");
        return true;
      } catch (RefreshException e) {
        emit(ownerId, connectionId, "USER_ACTION_REQUIRED", "Reconnect required: refresh failed");
        throw e;
      } catch (Exception e) {
        emit(ownerId, connectionId, "USER_ACTION_REQUIRED", "Reconnect required: refresh failed");
        throw new RefreshException("Reconnect required");
      }
    }
    String session = vault.getScoped(ownerId, connectionId, "session");
    if (session.isBlank()) {
      emit(ownerId, connectionId, "USER_ACTION_REQUIRED", "Reconnect required: no stored session");
      throw new RefreshException("Reconnect required");
    }
    // Saved browser session present: retry once without launching Chromium.
    connection.put("state", "CONNECTED");
    store.save("connections", connection, username);
    emit(ownerId, connectionId, "REFRESH_SUCCEEDED", "Reusing stored browser session");
    return true;
  }

  /** Refresh only when the stored access token is past its expiry. */
  public boolean ensureFresh(ObjectNode connection, String username) {
    String expiry = vault.getScoped(connection.path("ownerId").asText(), connection.path("id").asText(), "token_expiry");
    if (expiry.isBlank()) return false;
    try {
      if (Instant.parse(expiry).isAfter(Instant.now().plusSeconds(30))) return false;
    } catch (Exception e) {
      return false;
    }
    return refresh(connection, username);
  }

  private String tokenUrl(ObjectNode connection) {
    if (!connection.path("tokenUrl").asText("").isBlank()) return connection.path("tokenUrl").asText();
    String path = connection.path("tokenPath").asText("");
    if (!path.isBlank()) {
      return connection.path("baseUrl").asText().replaceAll("/$", "") + path;
    }
    return switch (connection.path("provider").asText()) {
      case "jira" -> "https://auth.atlassian.com/oauth/token";
      case "copilot" -> "https://login.microsoftonline.com/" + connection.path("tenant").asText("common")
        + "/oauth2/v2.0/token";
      default -> "";
    };
  }

  private void emit(String ownerId, String connectionId, String type, String message) {
    if (activity != null) activity.emit(ownerId, connectionId, null, type, message);
  }
}
