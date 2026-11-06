package com.apiintel.integrations;

import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

  private final IntegrationService service;
  private final Vault vault;
  private final RecordStore store;

  public IntegrationController(IntegrationService s, Vault v, RecordStore r) {
    service = s;
    vault = v;
    store = r;
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public Object save(@RequestBody ObjectNode data, Principal p) {
    return service.save(data, p.getName());
  }

  @PostMapping("/{id}/windows/check")
  @PreAuthorize("hasRole('ADMIN')")
  public Object checkWindows(@PathVariable String id){service.authorize(id);return service.checkWindows(id);}

  @GetMapping("/secrets")
  @PreAuthorize("hasRole('ADMIN')")
  public Object secrets() {
    return vault.names();
  }

  @PutMapping("/secrets/{name}")
  @PreAuthorize("hasRole('ADMIN')")
  public void secret(
    @PathVariable String name,
    @RequestBody Map<String, String> data,
    Principal p
  ) {
    vault.put(name, data.getOrDefault("value", ""));
    store.audit(p.getName(), "secret:rotate", name);
  }

  @DeleteMapping("/secrets/{name}")
  @PreAuthorize("hasRole('ADMIN')")
  public void remove(@PathVariable String name, Principal p) {
    vault.delete(name);
    store.audit(p.getName(), "secret:delete", name);
  }

  @PostMapping("/{id}/read")
  public Object read(
    @PathVariable String id,
    @RequestBody Map<String, String> data,
    Principal p
  ) {
    service.authorize(id);
    return service.call(
      service.config(id),
      "GET",
      data.getOrDefault("path", ""),
      null,
      p.getName()
    );
  }

  @PostMapping("/{id}/proposals")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object propose(
    @PathVariable String id,
    @RequestBody ObjectNode action,
    Principal p
  ) {
    service.authorize(id);
    return service.propose(id, action, p.getName());
  }

  @PostMapping("/approvals/{id}/approve")
  @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
  public Object approve(@PathVariable String id, Principal p) {
    return service.approve(id, p.getName());
  }

  @PostMapping("/approvals/{id}/execute")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object execute(@PathVariable String id, Principal p) {
    service.authorize(store.get("events", id).path("integrationId").asText());
    return service.execute(id, p.getName());
  }

  @PostMapping(
    value = "/approvals/{id}/stream",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE
  )
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Flux<String> streamApproval(@PathVariable String id, Principal p) {
    service.authorize(store.get("events", id).path("integrationId").asText());
    return service.executeStream(id, p.getName());
  }

  @PostMapping(
    value = "/{id}/friday/chat",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE
  )
  public Flux<String> stream(
    @PathVariable String id,
    @RequestBody ObjectNode data,
    Principal p
  ) {
    service.authorize(id);
    var c = service.config(id);
    if (
      !c.path("provider").asText().equals("friday")
    ) throw new IllegalArgumentException("Select a Friday connection");
    String ws = data.path("workspaceId").asText();
    if (!ws.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException(
      "Invalid workspace id"
    );
    store.audit(p.getName(), "friday:chat", id);
    return service.stream(
      c,
      "/api/workspaces/" + ws + "/chat",
      data.path("body")
    );
  }

  @PostMapping("/{id}/ai")
  public Object ai(
    @PathVariable String id,
    @RequestBody ObjectNode data,
    Principal p
  ) {
    return service.chat(id, data.path("prompt").asText(), p.getName());
  }

  @PostMapping("/{id}/jira/check")
  public Object checkJira(@PathVariable String id, Principal principal) {
    var config = jira(id);
    var profile = service.call(config, "GET", jiraApi(config) + "/myself", null, principal.getName());
    if (!profile.isObject() || !profile.hasNonNull("displayName")) {
      throw new IllegalArgumentException("Jira did not return an account profile. Check the site URL and token");
    }
    return Map.of("displayName", profile.path("displayName").asText());
  }

  @PostMapping("/{id}/jira/search")
  public Object searchJira(@PathVariable String id, @RequestBody Map<String, String> data, Principal principal) {
    var config = jira(id);
    String jql = data.getOrDefault("jql", "").trim();
    if (jql.isBlank() || jql.length() > 8000) throw new IllegalArgumentException("Enter JQL of up to 8,000 characters");
    String api = jiraApi(config);
    String endpoint = api.equals("/rest/api/2") ? "/search" : "/search/jql";
    String query = java.net.URLEncoder.encode(jql, java.nio.charset.StandardCharsets.UTF_8);
    return service.call(config, "GET", api + endpoint + "?jql=" + query, null, principal.getName());
  }

  private ObjectNode jira(String id) {
    service.authorize(id);
    var config = service.config(id);
    if (!config.path("provider").asText().equals("jira")) {
      throw new IllegalArgumentException("Select a Jira connection");
    }
    return config;
  }

  private String jiraApi(ObjectNode config) {
    return config.path("jiraDeployment").asText().equals("data-center") ? "/rest/api/2" : "/rest/api/3";
  }
}
