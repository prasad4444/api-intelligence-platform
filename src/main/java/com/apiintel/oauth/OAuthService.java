package com.apiintel.oauth;

import com.apiintel.integrations.ApprovedHosts;
import com.apiintel.integrations.ConnectionService;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generic OAuth authorization-code flow (PKCE + state) for personal
 * connections. Provider presets: jira → Atlassian 3LO, copilot → Entra v2.
 * Client id/secret come from the connection or a company OAuth app definition
 * ({@code oauthApp:true} on an integrations record). Well-known provider hosts
 * are auto-approved only while connecting that provider; everything else must
 * already be on the approved-host list.
 */
@Service
public class OAuthService {

  private record Pending(String ownerId, String connectionId, String verifier, Instant expires) {}

  private final RecordStore store;
  private final Vault vault;
  private final ConnectionService connections;
  private final ApprovedHosts approved;
  private final String publicUrl;
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  public OAuthService(RecordStore store, Vault vault, ConnectionService connections,
      ApprovedHosts approved, @Value("${platform.public-url:http://localhost:8080}") String publicUrl) {
    this.store = store;
    this.vault = vault;
    this.connections = connections;
    this.approved = approved;
    this.publicUrl = publicUrl.replaceAll("/$", "");
  }

  public String redirectUri() {
    return publicUrl + "/api/oauth/callback";
  }

  /** Start the flow; returns {authorizeUrl} or {requirement:{code,...}} when an app registration is missing. */
  public ObjectNode start(String connectionId, String username) {
    var connection = connections.getOwned(connectionId, username);
    var preset = preset(connection);
    String clientId = connection.path("clientId").asText("");
    if (clientId.isBlank()) clientId = companyAppClientId(connection);
    if (clientId.isBlank()) {
      var result = store.parse("{}");
      var requirement = result.putObject("requirement");
      if ("copilot".equals(connection.path("provider").asText())) {
        requirement.put("code", "ENTRA_APP_REGISTRATION");
        requirement.put("message",
          "Microsoft 365 Copilot needs a delegated Entra app registration "
            + "(Sites.Read.All, Mail.Read, People.Read.All, OnlineMeetingTranscript.Read.All, "
            + "Chat.Read, ChannelMessage.Read.All, ExternalItem.Read.All)");
        requirement.putArray("alternatives").add("CONNECTION_STUDIO");
      } else {
        requirement.put("code", "OAUTH_APP_REGISTRATION");
        requirement.put("message", "Register an OAuth app for this provider or use an API token");
        if ("jira".equals(connection.path("provider").asText())) {
          requirement.putArray("alternatives").add("JIRA_TOKEN");
        }
      }
      return result;
    }
    approved.approve(URI.create(preset.authorizeUrl).getHost() == null
      ? preset.authorizeUrl : "https://" + URI.create(preset.authorizeUrl).getHost());
    approved.approve("https://" + URI.create(preset.tokenUrl).getHost());
    String verifier = pkceVerifier();
    String state = UUID.randomUUID().toString();
    pending.values().removeIf(p -> p.expires().isBefore(Instant.now()));
    pending.put(state, new Pending(connection.path("ownerId").asText(), connectionId, verifier,
      Instant.now().plusSeconds(600)));
    String authorizeUrl = preset.authorizeUrl
      + (preset.authorizeUrl.contains("?") ? "&" : "?")
      + "client_id=" + enc(clientId)
      + "&redirect_uri=" + enc(redirectUri())
      + "&response_type=code"
      + "&state=" + enc(state)
      + "&code_challenge=" + enc(pkceChallenge(verifier))
      + "&code_challenge_method=S256"
      + preset.extraAuthorize;
    var result = store.parse("{}");
    result.put("authorizeUrl", authorizeUrl);
    result.put("state", state);
    return result;
  }

  /** Handle the provider redirect; returns the connection id on success. */
  public String callback(String code, String state) {
    var grant = pending.remove(state);
    if (grant == null || grant.expires().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state expired or unknown");
    }
    var connection = store.get("connections", grant.connectionId());
    if (!grant.ownerId().equals(connection.path("ownerId").asText())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OAuth state belongs to another user");
    }
    var preset = preset(connection);
    String clientId = connection.path("clientId").asText("");
    if (clientId.isBlank()) clientId = companyAppClientId(connection);
    String clientSecret = vault.getScoped(grant.ownerId(), grant.connectionId(), "client_secret");
    var form = new StringBuilder()
      .append("grant_type=authorization_code")
      .append("&code=").append(enc(code))
      .append("&redirect_uri=").append(enc(redirectUri()))
      .append("&client_id=").append(enc(clientId))
      .append("&code_verifier=").append(enc(grant.verifier()));
    if (!clientSecret.isBlank()) {
      form.append("&client_secret=").append(enc(clientSecret));
    }
    JsonNode token = post(URI.create(preset.tokenUrl), form.toString(), null);
    if (!token.hasNonNull("access_token")) {
      throw new IllegalArgumentException("Token endpoint returned no access_token");
    }
    String ownerId = grant.ownerId();
    vault.putScoped(ownerId, grant.connectionId(), "credential", "Bearer " + token.path("access_token").asText());
    if (token.hasNonNull("refresh_token")) {
      vault.putScoped(ownerId, grant.connectionId(), "refresh_token", token.path("refresh_token").asText());
    }
    if (token.hasNonNull("expires_in")) {
      vault.putScoped(ownerId, grant.connectionId(), "token_expiry",
        Instant.now().plusSeconds(token.path("expires_in").asLong()).toString());
    }
    connection.put("authType", "OAUTH");
    connection.put("hasCredential", true);
    connection.put("state", "CONNECTED");
    connection.put("lastError", "");
    if (preset.resourcesUrl != null) {
      try {
        JsonNode resources = get(URI.create(preset.resourcesUrl), token.path("access_token").asText());
        var sites = connection.putArray("jiraSites");
        if (resources.isArray()) {
          for (JsonNode resource : resources) {
            var site = sites.addObject();
            site.put("id", resource.path("id").asText(""));
            site.put("name", resource.path("name").asText(""));
            site.put("url", resource.path("url").asText(""));
          }
        }
        if (sites.size() == 1) connection.put("cloudId", sites.get(0).path("id").asText());
      } catch (RuntimeException e) {
        connection.put("lastError", "Connected, but accessible-resources failed: " + e.getMessage());
      }
    }
    String username = connection.path("owner").asText();
    store.save("connections", connection, username);
    store.audit(username, "oauth:connect", grant.connectionId());
    return grant.connectionId();
  }

  public String successRedirect(String connectionId) {
    return "/#connection-studio?connected=" + connectionId;
  }

  private record Preset(String authorizeUrl, String tokenUrl, String resourcesUrl, String extraAuthorize) {}

  private Preset preset(ObjectNode connection) {
    String provider = connection.path("provider").asText();
    String authorize = connection.path("authorizeUrl").asText("");
    String token = connection.path("tokenUrl").asText("");
    String scope = connection.path("scope").asText("");
    switch (provider) {
      case "jira" -> {
        String scopes = scope.isBlank()
          ? "read:jira-work write:jira-work read:jira-user offline_access" : scope;
        return new Preset(
          authorize.isBlank() ? "https://auth.atlassian.com/authorize?audience=api.atlassian.com&prompt=consent" : authorize,
          token.isBlank() ? "https://auth.atlassian.com/oauth/token" : token,
          connection.path("resourcesUrl").asText("").isBlank()
            ? "https://api.atlassian.com/oauth/token/accessible-resources"
            : connection.path("resourcesUrl").asText(),
          "&scope=" + enc(scopes));
      }
      case "copilot" -> {
        String tenant = connection.path("tenant").asText("common");
        String scopes = scope.isBlank()
          ? "Sites.Read.All Mail.Read People.Read.All OnlineMeetingTranscript.Read.All Chat.Read ChannelMessage.Read.All ExternalItem.Read.All offline_access"
          : scope;
        return new Preset(
          authorize.isBlank() ? "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/authorize" : authorize,
          token.isBlank() ? "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token" : token,
          null,
          "&scope=" + enc(scopes));
      }
      default -> {
        if (authorize.isBlank() || token.isBlank()) {
          throw new IllegalArgumentException("Configure authorizeUrl and tokenUrl for OAuth on this provider");
        }
        return new Preset(authorize, token, null,
          scope.isBlank() ? "" : "&scope=" + enc(scope));
      }
    }
  }

  private String companyAppClientId(ObjectNode connection) {
    for (ObjectNode definition : store.list("integrations")) {
      if (!definition.path("oauthApp").asBoolean(false)) continue;
      if (!definition.path("provider").asText().equals(connection.path("provider").asText())) continue;
      return definition.path("clientId").asText("");
    }
    return "";
  }

  private JsonNode post(URI uri, String form, String bearer) {
    try {
      var request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
      var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        .send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalArgumentException("Token endpoint returned HTTP " + response.statusCode());
      }
      return json.readTree(response.body());
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("OAuth token exchange failed");
    }
  }

  private JsonNode get(URI uri, String bearer) {
    try {
      var request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(20))
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + bearer)
        .GET()
        .build();
      var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        .send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalArgumentException("Provider returned HTTP " + response.statusCode());
      }
      return json.readTree(response.body());
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("OAuth resource request failed");
    }
  }

  private static String pkceVerifier() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String pkceChallenge(String verifier) {
    try {
      return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String enc(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
