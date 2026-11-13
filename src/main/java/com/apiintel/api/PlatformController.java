package com.apiintel.api;

import com.apiintel.security.PlatformUserService;
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
  private final com.apiintel.workspace.WorkspaceArtifacts artifacts;
  private final PlatformUserService users;

  public PlatformController(
    RecordStore store,
    com.apiintel.workspace.SpecService specs,
    com.apiintel.workspace.WorkspaceArtifacts artifacts,
    PlatformUserService users
  ) {
    this.store = store;
    this.specs = specs;
    this.artifacts = artifacts;
    this.users = users;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "header", token.getHeaderName());
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication a) {
    var profile = users.findByUsername(a.getName()).map(users::publicView).orElse(Map.of());
    var out = new LinkedHashMap<String, Object>();
    out.put("name", a.getName());
    out.put("roles", a.getAuthorities());
    out.put("id", profile.getOrDefault("id", ""));
    out.put("displayName", profile.getOrDefault("displayName", a.getName()));
    return out;
  }

  @GetMapping("/records/{kind}")
  public List<ObjectNode> list(@PathVariable String kind) {
    checkPublicKind(kind);
    return store.list(kind);
  }

  @GetMapping("/records/{kind}/{id}")
  public ObjectNode get(@PathVariable String kind, @PathVariable String id) {
    checkPublicKind(kind);
    return store.get(kind, id);
  }

  @PostMapping("/records/{kind}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  @org.springframework.transaction.annotation.Transactional
  public ObjectNode save(
    @PathVariable String kind,
    @RequestBody ObjectNode data,
    Principal p
  ) {
    checkPublicKind(kind);
    if (
      Set.of("environments", "integrations", "rules", "events", "governance-runs").contains(
        kind
      )
    ) throw new IllegalArgumentException(
      "Use the dedicated settings endpoints"
    );
    if (kind.equals("specs")) {
      var parsed=data.has("source")?specs.parse(data.path("source").asText()):specs.parse(data.path("document").toString());
      data.put("contractHash",com.apiintel.workspace.WorkspaceArtifacts.hash(parsed));
      data.set("document",parsed);if(!data.has("source"))data.put("source",parsed.toPrettyString());
      data.put("name",parsed.path("info").path("title").asText("API"));data.put("version",parsed.path("info").path("version").asText("1.0"));
      data.remove(java.util.List.of("reviewer","reviewRequestedBy"));
      var status = data.path("status").asText("DRAFT");
      if (!status.equals("DRAFT")) throw new IllegalArgumentException(
        "Editing a spec requires DRAFT status; approval must be requested again"
      );
    }
    var saved=store.save(kind, data, p.getName());
    if(kind.equals("specs"))artifacts.prepare(saved,p.getName());
    return saved;
  }

  @DeleteMapping("/records/{kind}/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public void delete(
    @PathVariable String kind,
    @PathVariable String id,
    Principal p
  ) {
    checkPublicKind(kind);
    if (kind.equals("environments")) throw new IllegalArgumentException("Use the environments endpoint");
    store.delete(kind, id, p.getName());
  }

  @GetMapping("/records/{kind}/{id}/versions")
  public List<ObjectNode> versions(
    @PathVariable String kind,
    @PathVariable String id
  ) {
    checkPublicKind(kind);
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
  private void checkPublicKind(String kind) {
    if (Set.of("ai-drafts", "ai-checks", "connections", "captured-requests", "discovery-sessions").contains(kind)) {
      throw new IllegalArgumentException("Use the dedicated connection or discovery endpoints");
    }
  }
}
