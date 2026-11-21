package com.apiintel.connectors;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connectors")
@PreAuthorize("isAuthenticated()")
public class ConnectorController {

  private final ConnectorService connectors;

  public ConnectorController(ConnectorService connectors) {
    this.connectors = connectors;
  }

  @PostMapping("/{connectionId}/versions")
  public ObjectNode saveVersion(@PathVariable String connectionId,
                                @RequestBody ObjectNode body, Principal p) {
    return connectors.saveVersion(connectionId, body, p.getName());
  }

  @GetMapping("/{connectionId}/versions")
  public List<ObjectNode> versions(@PathVariable String connectionId, Principal p) {
    return connectors.versions(connectionId, p.getName());
  }

  @PostMapping("/versions/{id}/validate")
  public ObjectNode markValidated(@PathVariable String id, Principal p) {
    return connectors.markValidated(id, p.getName());
  }

  @PostMapping("/versions/{id}/activate")
  public ObjectNode activate(@PathVariable String id, Principal p) {
    return connectors.activate(id, p.getName());
  }

  @GetMapping("/health")
  public List<ObjectNode> health(Principal p) {
    return connectors.health(p.getName());
  }

  @PostMapping("/{connectionId}/codegen")
  public ObjectNode codegen(@PathVariable String connectionId,
                            @RequestBody ObjectNode body, Principal p) {
    return connectors.codegen(connectionId, body, p.getName());
  }

  @PostMapping("/{connectionId}/codegen/validate")
  public ObjectNode validateGenerated(@PathVariable String connectionId,
                                      @RequestBody ObjectNode body, Principal p) {
    return connectors.validateGenerated(connectionId, body, p.getName());
  }

  @PostMapping("/{connectionId}/codegen/pr")
  public ObjectNode proposePr(@PathVariable String connectionId,
                              @RequestBody ObjectNode body, Principal p) {
    return connectors.proposePr(connectionId, body, p.getName());
  }

  @GetMapping("/catalog")
  public ArrayNode catalog(Principal p) {
    return connectors.catalog(p.getName());
  }

  @PostMapping("/catalog/{id}/apply")
  public ObjectNode applyCatalog(@PathVariable String id,
                                 @RequestBody(required = false) ObjectNode body, Principal p) {
    return connectors.applyCatalog(id,
        body == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : body,
        p.getName());
  }

  @GetMapping("/dashboard")
  public ObjectNode dashboard(@RequestParam(defaultValue = "") String service, Principal p) {
    return connectors.dashboard(service, p.getName());
  }

  @GetMapping("/correlation/{correlationId}")
  public ObjectNode correlation(@PathVariable String correlationId, Principal p) {
    return connectors.correlation(correlationId, p.getName());
  }

  @GetMapping("/playbooks")
  public List<ObjectNode> playbooks(Principal p) {
    return connectors.playbooks(p.getName());
  }

  @PostMapping("/playbooks")
  public ObjectNode savePlaybook(@RequestBody ObjectNode body, Principal p) {
    return connectors.savePlaybook(body, p.getName());
  }

  @PostMapping("/investigations/{investigationId}/playbooks/{playbookId}/run")
  public ObjectNode runPlaybook(@PathVariable String investigationId,
                                @PathVariable String playbookId, Principal p) {
    return connectors.runPlaybook(investigationId, playbookId, p.getName());
  }

  @GetMapping("/cost-limits")
  public List<ObjectNode> costLimits(Principal p) {
    return connectors.costLimits(p.getName());
  }

  @PostMapping("/cost-limits")
  public ObjectNode saveCostLimit(@RequestBody ObjectNode body, Principal p) {
    return connectors.saveCostLimit(body, p.getName());
  }

  @DeleteMapping("/cost-limits/{id}")
  public void deleteCostLimit(@PathVariable String id, Principal p) {
    connectors.deleteCostLimit(id, p.getName());
  }
}
