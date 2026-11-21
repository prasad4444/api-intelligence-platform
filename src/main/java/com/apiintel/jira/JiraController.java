package com.apiintel.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("isAuthenticated()")
public class JiraController {

  private final JiraService jira;

  public JiraController(JiraService jira) {
    this.jira = jira;
  }

  @GetMapping("/api/jira/{connectionId}/search")
  public JsonNode search(@PathVariable String connectionId,
      @RequestParam String jql,
      @RequestParam(required = false) String nextPageToken,
      @RequestParam(required = false) Integer startAt,
      Principal principal) {
    return jira.search(connectionId, jql, nextPageToken, startAt, principal.getName());
  }

  @GetMapping("/api/jira/{connectionId}/issue/{key}")
  public JsonNode issue(@PathVariable String connectionId, @PathVariable String key, Principal principal) {
    return jira.issue(connectionId, key, principal.getName());
  }

  @PostMapping("/api/jira/{connectionId}/issues/propose")
  public ObjectNode proposeCreate(@PathVariable String connectionId, @RequestBody ObjectNode body,
      Principal principal) {
    return jira.proposeIssue(connectionId, null,
      body.path("fields") instanceof ObjectNode fields ? fields : null, principal.getName());
  }

  @PostMapping("/api/jira/{connectionId}/issues/{key}/propose")
  public ObjectNode proposeUpdate(@PathVariable String connectionId, @PathVariable String key,
      @RequestBody ObjectNode body, Principal principal) {
    return jira.proposeIssue(connectionId, key,
      body.path("fields") instanceof ObjectNode fields ? fields : null, principal.getName());
  }

  @PostMapping("/api/connections/{connectionId}/jira-site")
  public ObjectNode jiraSite(@PathVariable String connectionId, @RequestBody ObjectNode body,
      Principal principal) {
    return jira.selectSite(connectionId, body.path("cloudId").asText(""), principal.getName());
  }
}
