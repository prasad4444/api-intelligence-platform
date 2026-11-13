package com.apiintel.integrations;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/discovery")
@PreAuthorize("isAuthenticated()")
public class DiscoveryController {

  private final DiscoveryService discovery;
  private final ReplayService replay;

  public DiscoveryController(DiscoveryService discovery, ReplayService replay) {
    this.discovery = discovery;
    this.replay = replay;
  }

  @PostMapping("/sessions")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode start(@RequestBody ObjectNode body, Principal principal) {
    return discovery.start(body, principal.getName());
  }

  @PostMapping("/sessions/{id}/cancel")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode cancel(@PathVariable String id, Principal principal) {
    return discovery.cancel(id, principal.getName());
  }

  @PostMapping("/sessions/{id}/save")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode save(@PathVariable String id, @RequestBody ObjectNode body, Principal principal) {
    return discovery.saveSelected(id, body, principal.getName());
  }

  @GetMapping("/requests")
  public List<ObjectNode> requests(Principal principal) {
    return discovery.listSaved(principal.getName());
  }

  @PostMapping("/requests/{id}/replay")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode replay(
    @PathVariable String id,
    @RequestBody(required = false) ObjectNode body,
    Principal principal
  ) {
    return replay.replay(id, body == null ? bodyEmpty() : body, principal.getName());
  }

  private ObjectNode bodyEmpty() {
    return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
  }
}
