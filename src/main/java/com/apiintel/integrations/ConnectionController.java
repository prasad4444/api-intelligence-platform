package com.apiintel.integrations;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connections")
@PreAuthorize("isAuthenticated()")
public class ConnectionController {

  private final ConnectionService connections;

  public ConnectionController(ConnectionService connections) {
    this.connections = connections;
  }

  @GetMapping
  public List<ObjectNode> mine(Principal principal) {
    return connections.listMine(principal.getName());
  }

  @GetMapping("/definitions")
  public List<ObjectNode> definitions() {
    return connections.listDefinitions();
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode connect(@RequestBody ObjectNode body, Principal principal) {
    return connections.connect(body, principal.getName());
  }

  @PostMapping("/{id}/test")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode test(
    @PathVariable String id,
    @RequestBody(required = false) ObjectNode body,
    Principal principal
  ) {
    boolean synthetic = body != null && body.path("confirmSyntheticPrompt").asBoolean(false);
    return connections.verify(id, principal.getName(), synthetic);
  }

  @PostMapping("/{id}/reconnect")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode reconnect(@PathVariable String id, @RequestBody ObjectNode body, Principal principal) {
    body.put("id", id);
    return connections.connect(body, principal.getName());
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode disconnect(@PathVariable String id, Principal principal) {
    return connections.disconnect(id, principal.getName());
  }

  @GetMapping("/{id}")
  public ObjectNode get(@PathVariable String id, Principal principal) {
    return connections.redact(connections.getOwned(id, principal.getName()));
  }

  @GetMapping("/providers")
  public Map<String, Object> providers() {
    return Map.of("providers", ConnectionService.PROVIDERS, "states", ConnectionService.STATES);
  }
}
