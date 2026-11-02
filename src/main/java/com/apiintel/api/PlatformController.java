package com.apiintel.api;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PlatformController {

  private final RecordStore store;
  private final com.apiintel.workspace.SpecService specs;

  public PlatformController(RecordStore store, com.apiintel.workspace.SpecService specs) {
    this.store = store;
    this.specs = specs;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "header", token.getHeaderName());
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication a) {
    return Map.of("name", a.getName(), "roles", a.getAuthorities());
  }

  @GetMapping("/records/{kind}")
  public List<ObjectNode> list(@PathVariable String kind) {
    return store.list(kind);
  }

  @GetMapping("/records/{kind}/{id}")
  public ObjectNode get(@PathVariable String kind, @PathVariable String id) {
    return store.get(kind, id);
  }

  @PostMapping("/records/{kind}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode save(
    @PathVariable String kind,
    @RequestBody ObjectNode data,
    Principal p
  ) {
    if (
      Set.of("environments", "integrations", "rules", "events", "governance-runs").contains(
        kind
      )
    ) throw new IllegalArgumentException(
      "Use the dedicated settings endpoints"
    );
    if (kind.equals("specs")) {
      specs.parse(data.path("document").toString());
      var status = data.path("status").asText("DRAFT");
      if (!status.equals("DRAFT")) throw new IllegalArgumentException(
        "Editing a spec requires DRAFT status; approval must be requested again"
      );
    }
    return store.save(kind, data, p.getName());
  }

  @DeleteMapping("/records/{kind}/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public void delete(
    @PathVariable String kind,
    @PathVariable String id,
    Principal p
  ) {
    if (kind.equals("environments")) throw new IllegalArgumentException("Use the environments endpoint");
    store.delete(kind, id, p.getName());
  }

  @GetMapping("/records/{kind}/{id}/versions")
  public List<ObjectNode> versions(
    @PathVariable String kind,
    @PathVariable String id
  ) {
    return store.versions(kind, id);
  }

  @PostMapping("/records/{kind}/{id}/restore/{revision}")
  @PreAuthorize("hasAnyRole('ADMIN','TESTER')")
  public ObjectNode restore(
    @PathVariable String kind,
    @PathVariable String id,
    @PathVariable int revision,
    Principal p
  ) {
    if (!kind.equals("tests")) throw new IllegalArgumentException(
      "Only test cases support restore"
    );
    return store.restore(kind, id, revision, p.getName());
  }

  @GetMapping("/audit")
  @PreAuthorize("hasRole('ADMIN')")
  public List<Map<String, Object>> audit() {
    return store.audit();
  }
}
