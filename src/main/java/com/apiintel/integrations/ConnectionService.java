package com.apiintel.integrations;

import com.apiintel.security.PlatformUserService;
import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConnectionService {

  public static final Set<String> PROVIDERS = Set.of(
    "jira", "github", "github-copilot", "copilot", "devin", "stylus", "friday",
    "openai", "anthropic", "azure", "ollama", "custom"
  );
  public static final Set<String> STATES = Set.of(
    "NOT_CONNECTED", "CONNECTED", "RECONNECT_REQUIRED", "ERROR"
  );

  private final RecordStore store;
  private final Vault vault;
  private final PlatformUserService users;
  private final IntegrationService integrations;
  private final ApprovedHosts approved;

  public ConnectionService(
    RecordStore store,
    Vault vault,
    PlatformUserService users,
    IntegrationService integrations,
    ApprovedHosts approved
  ) {
    this.store = store;
    this.vault = vault;
    this.users = users;
    this.integrations = integrations;
    this.approved = approved;
  }

  public List<ObjectNode> listMine(String username) {
    var owner = users.requireUsername(username);
    String ownerId = String.valueOf(owner.get("id"));
    return store.list("connections").stream()
      .filter(c -> ownerId.equals(c.path("ownerId").asText()))
      .map(this::redact)
      .toList();
  }

  public List<ObjectNode> listDefinitions() {
    return store.list("integrations").stream().map(integrations::publicDefinition).toList();
  }

  public ObjectNode getOwned(String id, String username) {
    var connection = store.get("connections", id);
    assertOwner(connection, username);
    return connection;
  }

  public ObjectNode connect(ObjectNode input, String username) {
    var owner = users.requireUsername(username);
    String ownerId = String.valueOf(owner.get("id"));
    String provider = input.path("provider").asText();
    if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("Unsupported provider");
    ObjectNode connection;
    if (input.hasNonNull("id")) {
      connection = getOwned(input.path("id").asText(), username);
    } else {
      connection = store.parse("{}");
      connection.put("ownerId", ownerId);
      connection.put("owner", username);
      connection.put("provider", provider);
      connection.put("state", "NOT_CONNECTED");
      connection.put("enabled", true);
      connection.putArray("allowedRoles").add("ADMIN").add("DEVELOPER").add("TESTER");
    }
    if (input.hasNonNull("definitionId")) {
      var definition = store.get("integrations", input.path("definitionId").asText());
      connection.put("definitionId", definition.path("id").asText());
      mergeDefinition(connection, definition);
    }
    copyIfPresent(input, connection, "name", "baseUrl", "authType", "jiraEmail", "jiraDeployment",
      "headerName", "clientId", "tokenPath", "scope", "organizationId", "workspaceId", "model",
      "chatPath", "aiChatEnabled", "aiMethod", "aiProbePath", "aiResponseFormat", "aiResponsePointer",
      "aiRequiresApproval", "testGenerationEnabled", "aiRequestTemplate");
    if (input.has("aiHeaders") && input.path("aiHeaders").isObject()) {
      connection.set("aiHeaders", input.path("aiHeaders"));
    }
    if (input.has("eventSignals") && input.path("eventSignals").isObject()) {
      connection.set("eventSignals", input.path("eventSignals"));
    }
    String name = connection.path("name").asText();
    if (name.isBlank()) connection.put("name", provider + " · " + username);
    String baseUrl = connection.path("baseUrl").asText();
    if (baseUrl.isBlank()) throw new IllegalArgumentException("Enter the provider base URL");
    URI uri = URI.create(baseUrl);
    connection.put("siteHost", uri.getHost() == null ? "" : uri.getHost());
    approved.approve(baseUrl);
    String credential = input.path("credential").asText("");
    connection = store.save("connections", connection, username);
    if (!credential.isBlank()) {
      vault.putScoped(ownerId, connection.path("id").asText(), "credential", credential);
      connection.put("secretRef", "scoped:credential");
      connection.put("hasCredential", true);
      connection = store.save("connections", connection, username);
    }
    if (!connection.path("hasCredential").asBoolean() && !"NONE".equals(connection.path("authType").asText("NONE"))) {
      connection.put("state", "NOT_CONNECTED");
      return redact(store.save("connections", connection, username));
    }
    return redact(verify(connection.path("id").asText(), username, false));
  }

  public ObjectNode verify(String id, String username, boolean syntheticAiAllowed) {
    var connection = getOwned(id, username);
    try {
      var runtime = runtimeConfig(connection);
      String provider = connection.path("provider").asText();
      String identity = "";
      switch (provider) {
        case "jira" -> {
          var profile = integrations.call(runtime, "GET", jiraApi(runtime) + "/myself", null, username);
          identity = profile.path("displayName").asText(profile.path("accountId").asText("jira-user"));
        }
        case "github" -> {
          var profile = integrations.call(runtime, "GET", "/user", null, username);
          identity = profile.path("login").asText("github-user");
        }
        case "github-copilot", "copilot", "devin", "stylus", "friday", "openai", "anthropic", "azure", "ollama", "custom" -> {
          if (runtime.path("aiChatEnabled").asBoolean() || Set.of("openai", "anthropic", "azure", "ollama", "friday").contains(provider)) {
            if (runtime.path("aiProbePath").asText().isBlank()
                && !AiEndpointSettings.method(runtime).equals("GET")
                && !syntheticAiAllowed
                && runtime.path("aiChatEnabled").asBoolean()) {
              throw new IllegalArgumentException("Confirm a synthetic probe or configure aiProbePath before verifying AI access");
            }
            if (runtime.path("aiChatEnabled").asBoolean() || runtime.has("aiMethod")) {
              integrations.testAi(runtime, username);
              identity = runtime.path("siteHost").asText(provider);
            } else if (provider.equals("friday")) {
              integrations.call(runtime, "GET", "/api/workspaces", null, username);
              identity = "friday";
            } else {
              identity = runtime.path("siteHost").asText(provider);
            }
          } else {
            identity = runtime.path("siteHost").asText(provider);
          }
        }
        default -> identity = runtime.path("siteHost").asText(provider);
      }
      connection.put("state", "CONNECTED");
      connection.put("verifiedIdentity", identity);
      connection.put("lastError", "");
      connection.put("enabled", true);
    } catch (IllegalArgumentException e) {
      String message = e.getMessage() == null ? "Connection failed" : e.getMessage();
      boolean reconnect = message.toLowerCase(Locale.ROOT).contains("401")
        || message.toLowerCase(Locale.ROOT).contains("expired")
        || message.toLowerCase(Locale.ROOT).contains("reconnect");
      connection.put("state", reconnect ? "RECONNECT_REQUIRED" : "ERROR");
      connection.put("lastError", message);
      connection.put("verifiedIdentity", "");
    }
    return redact(store.save("connections", connection, username));
  }

  public ObjectNode disconnect(String id, String username) {
    var connection = getOwned(id, username);
    vault.deleteConnection(connection.path("ownerId").asText(), connection.path("id").asText());
    invalidatePending(connection, username);
    connection.put("state", "NOT_CONNECTED");
    connection.put("verifiedIdentity", "");
    connection.put("hasCredential", false);
    connection.put("hasSession", false);
    connection.put("lastError", "");
    connection.put("secretRef", "");
    connection.put("enabled", false);
    store.audit(username, "connection:disconnect", id);
    return redact(store.save("connections", connection, username));
  }

  public ObjectNode runtimeConfig(ObjectNode connection) {
    boolean usable = connection.path("hasCredential").asBoolean()
      || Set.of("CONNECTED", "ERROR", "RECONNECT_REQUIRED").contains(connection.path("state").asText())
      || "NONE".equals(connection.path("authType").asText("NONE"));
    if (!usable) {
      throw new IllegalArgumentException("Connect your own account before using this provider");
    }
    var runtime = connection.deepCopy();
    runtime.put("personal", true);
    if (!runtime.hasNonNull("authType")) runtime.put("authType", "BEARER");
    runtime.put("enabled", true);
    return runtime;
  }

  public ObjectNode resolve(String id, String username) {
    try {
      var connection = getOwned(id, username);
      return runtimeConfig(connection);
    } catch (ResponseStatusException e) {
      if (e.getStatusCode() != HttpStatus.NOT_FOUND) throw e;
      integrations.authorize(id);
      return integrations.config(id);
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().contains("User not found")) throw e;
      integrations.authorize(id);
      return integrations.config(id);
    }
  }

  public ObjectNode redact(ObjectNode connection) {
    var view = connection.deepCopy();
    view.remove(List.of("credential", "password", "token", "clientSecret", "cookieJar", "sessionState"));
    view.put("hasCredential", !vault.getScoped(
      connection.path("ownerId").asText(),
      connection.path("id").asText(),
      "credential"
    ).isBlank() || connection.path("hasCredential").asBoolean());
    view.put("hasSession", !vault.getScoped(
      connection.path("ownerId").asText(),
      connection.path("id").asText(),
      "session"
    ).isBlank() || connection.path("hasSession").asBoolean());
    return view;
  }

  public void assertOwner(ObjectNode connection, String username) {
    var owner = users.requireUsername(username);
    if (!String.valueOf(owner.get("id")).equals(connection.path("ownerId").asText())) {
      throw new AccessDeniedException("This connection belongs to another user");
    }
  }

  public void markReconnect(String id, String username, String message) {
    var connection = getOwned(id, username);
    connection.put("state", "RECONNECT_REQUIRED");
    connection.put("lastError", message);
    store.save("connections", connection, username);
  }

  private void invalidatePending(ObjectNode connection, String username) {
    String id = connection.path("id").asText();
    for (var event : store.list("events")) {
      if (!id.equals(event.path("integrationId").asText()) && !id.equals(event.path("connectionId").asText())) continue;
      String status = event.path("status").asText();
      if (Set.of("PENDING", "APPROVED").contains(status)) {
        event.put("status", "CANCELLED");
        event.put("cancelReason", "Connection disconnected");
        store.save("events", event, username);
      }
    }
  }

  private void mergeDefinition(ObjectNode connection, ObjectNode definition) {
    for (String field : List.of(
      "provider", "baseUrl", "authType", "headerName", "jiraDeployment", "clientId", "tokenPath",
      "scope", "organizationId", "workspaceId", "model", "chatPath", "aiChatEnabled", "aiMethod",
      "aiProbePath", "aiResponseFormat", "aiResponsePointer", "aiRequiresApproval",
      "testGenerationEnabled", "aiRequestTemplate"
    )) {
      if (definition.has(field) && !definition.path(field).isMissingNode()) {
        connection.set(field, definition.get(field).deepCopy());
      }
    }
    if (definition.has("aiHeaders")) connection.set("aiHeaders", definition.path("aiHeaders").deepCopy());
    if (definition.has("eventSignals")) connection.set("eventSignals", definition.path("eventSignals").deepCopy());
    if (connection.path("name").asText().isBlank()) {
      connection.put("name", definition.path("name").asText(definition.path("provider").asText()) + " (personal)");
    }
  }

  private void copyIfPresent(ObjectNode input, ObjectNode target, String... fields) {
    for (String field : fields) {
      if (input.has(field) && !input.path(field).isNull()) target.set(field, input.get(field).deepCopy());
    }
  }

  private String jiraApi(ObjectNode config) {
    return config.path("jiraDeployment").asText().equals("data-center") ? "/rest/api/2" : "/rest/api/3";
  }
}
