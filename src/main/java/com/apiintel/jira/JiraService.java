package com.apiintel.jira;

import com.apiintel.integrations.ConnectionService;
import com.apiintel.integrations.IntegrationService;
import com.apiintel.oauth.TokenRefresher;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Jira read APIs bound to the requesting user's own connection.
 * Routing:
 *  - OAuth 3LO:        https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/...
 *  - email+API token:  {site}/rest/api/3/... (Basic auth handled by JIRA_TOKEN)
 *  - data-center:      {site}/rest/api/2/... with startAt pagination
 * Cloud search uses /rest/api/3/search/jql with nextPageToken (the legacy
 * /search endpoint is removed from Jira Cloud).
 */
@Service
public class JiraService implements IntegrationService.PersonalConnectionExecutor {

  public static final String ATLASSIAN_API = "https://api.atlassian.com";

  private final RecordStore store;
  private final ConnectionService connections;
  private final IntegrationService integrations;
  private final TokenRefresher refresher;

  public JiraService(RecordStore store, ConnectionService connections,
      IntegrationService integrations, TokenRefresher refresher) {
    this.store = store;
    this.connections = connections;
    this.integrations = integrations;
    this.refresher = refresher;
  }

  public JsonNode search(String connectionId, String jql, String nextPageToken, Integer startAt, String username) {
    var connection = connections.getOwned(connectionId, username);
    if (jql == null || jql.isBlank() || jql.length() > 4000) {
      throw new IllegalArgumentException("Enter a JQL query");
    }
    var body = store.parse("{}");
    body.put("jql", jql);
    body.put("maxResults", 25);
    if (oauth(connection)) {
      if (nextPageToken != null && !nextPageToken.isBlank()) body.put("nextPageToken", nextPageToken);
      var runtime = oauthRuntime(connection, username);
      return integrations.call(runtime, "POST", api(connection, "/search/jql"), body, username);
    }
    if (dataCenter(connection)) {
      body.put("startAt", startAt == null ? 0 : Math.max(0, startAt));
      var runtime = connections.runtimeConfig(connection);
      return integrations.call(runtime, "POST", "/rest/api/2/search", body, username);
    }
    if (nextPageToken != null && !nextPageToken.isBlank()) body.put("nextPageToken", nextPageToken);
    var runtime = connections.runtimeConfig(connection);
    return integrations.call(runtime, "POST", "/rest/api/3/search/jql", body, username);
  }

  public JsonNode issue(String connectionId, String key, String username) {
    var connection = connections.getOwned(connectionId, username);
    if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]+-\\d+")) {
      throw new IllegalArgumentException("Enter a Jira issue key like ABC-123");
    }
    if (oauth(connection)) {
      var runtime = oauthRuntime(connection, username);
      return integrations.call(runtime, "GET", api(connection, "/issue/" + key), null, username);
    }
    var runtime = connections.runtimeConfig(connection);
    String base = dataCenter(connection) ? "/rest/api/2" : "/rest/api/3";
    return integrations.call(runtime, "GET", base + "/issue/" + key, null, username);
  }

  public ObjectNode selectSite(String connectionId, String cloudId, String username) {
    var connection = connections.getOwned(connectionId, username);
    if (cloudId == null || !cloudId.matches("[a-zA-Z0-9-]{6,80}")) {
      throw new IllegalArgumentException("Choose one of the accessible Jira sites");
    }
    var sites = connection.path("jiraSites");
    boolean found = false;
    if (sites.isArray()) {
      for (JsonNode site : sites) {
        if (cloudId.equals(site.path("id").asText())) found = true;
      }
    }
    if (sites.isArray() && sites.size() > 0 && !found) {
      throw new IllegalArgumentException("That site is not in the accessible-resources list for this account");
    }
    connection.put("cloudId", cloudId);
    return connections.redact(store.save("connections", connection, username));
  }

  /**
   * Propose a Jira issue write through the approvals pipeline. The stored
   * action carries the fully routed path for this connection's auth mode; the
   * approval event binds connectionId, revision, ownerId and payload
   * fingerprint. Approve/execute reuse /api/integrations/approvals endpoints.
   */
  public ObjectNode proposeIssue(String connectionId, String key, ObjectNode fields, String username) {
    var connection = connections.getOwned(connectionId, username);
    if (key != null && !key.matches("[A-Za-z][A-Za-z0-9_]+-\\d+")) {
      throw new IllegalArgumentException("Enter a Jira issue key like ABC-123");
    }
    if (fields == null || !fields.isObject() || fields.isEmpty()) {
      throw new IllegalArgumentException("Provide the Jira issue fields");
    }
    if (fields.toString().length() > 200_000) {
      throw new IllegalArgumentException("Issue fields are too large");
    }
    String suffix = "/issue" + (key == null ? "" : "/" + key);
    ObjectNode routing;
    String path;
    if (oauth(connection)) {
      routing = oauthRuntime(connection, username);
      path = api(connection, suffix);
    } else {
      routing = connections.runtimeConfig(connection);
      path = (dataCenter(connection) ? "/rest/api/2" : "/rest/api/3") + suffix;
    }
    var action = store.parse("{}")
      .put("method", key == null ? "POST" : "PUT")
      .put("path", path);
    var body = store.parse("{}");
    body.set("fields", fields);
    action.set("body", body);
    return integrations.propose(routing, connectionId, action, username);
  }

  /** Personal Jira approvals are executed by this service, not the generic path. */
  @Override
  public boolean supports(ObjectNode event, ObjectNode config) {
    return "jira".equals(config.path("provider").asText())
      && config.path("ownerId").isTextual()
      && !config.path("ownerId").asText().isBlank();
  }

  /**
   * Executes an approved Jira write on the requesting user's own connection.
   * Re-asserts that event owner == connection owner == actor, that the
   * connection is still CONNECTED, refreshes OAuth credentials via
   * {@link TokenRefresher#ensureFresh}, then dispatches only the stored action
   * path/body.
   */
  @Override
  public JsonNode execute(ObjectNode event, ObjectNode connection, String actor) {
    if (!connection.path("owner").asText("").equals(actor)
        || !actor.equals(event.path("owner").asText(""))
        || !actor.equals(event.path("requester").asText(""))) {
      throw new org.springframework.security.access.AccessDeniedException(
        "This approval belongs to another user");
    }
    connections.assertOwner(connection, actor);
    if (!"CONNECTED".equals(connection.path("state").asText(""))) {
      throw new IllegalArgumentException("Reconnect required before executing this approval");
    }
    var runtime = oauth(connection)
      ? oauthRuntime(connection, actor)
      : connections.runtimeConfig(connection);
    var action = event.path("action");
    event.put("status", "EXECUTING");
    event = store.save("events", event, actor);
    try {
      var result = integrations.call(runtime, action.path("method").asText(),
        action.path("path").asText(), action.path("body"), actor);
      event.put("status", "COMPLETED");
      store.save("events", event, actor);
      return result;
    } catch (RuntimeException failure) {
      event.put("status", "FAILED");
      store.save("events", event, actor);
      throw failure;
    }
  }

  public boolean oauth(ObjectNode connection) {
    return !connection.path("cloudId").asText("").isBlank()
      || "OAUTH".equals(connection.path("authType").asText(""));
  }

  private boolean dataCenter(ObjectNode connection) {
    return "data-center".equals(connection.path("jiraDeployment").asText(""));
  }

  private String api(ObjectNode connection, String path) {
    return "/ex/jira/" + connection.path("cloudId").asText() + "/rest/api/3" + path;
  }

  private ObjectNode oauthRuntime(ObjectNode connection, String username) {
    try {
      refresher.ensureFresh(connection, username);
    } catch (RuntimeException e) {
      connections.markReconnect(connection.path("id").asText(), username, "Reconnect required");
      throw e;
    }
    var runtime = connections.runtimeConfig(connection);
    runtime.put("baseUrl", connection.path("apiBase").asText("").isBlank()
      ? ATLASSIAN_API : connection.path("apiBase").asText());
    runtime.put("authType", "AUTHORIZATION");
    return runtime;
  }
}
