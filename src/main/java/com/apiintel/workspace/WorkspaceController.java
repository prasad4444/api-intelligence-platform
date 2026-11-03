package com.apiintel.workspace;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

  private final com.apiintel.integrations.FridayEvents friday;
  private final SpecService specs;
  private final WorkspaceArtifacts artifacts;
  private final RecordStore store;

  public WorkspaceController(
    SpecService specs,
    RecordStore store,
    com.apiintel.integrations.FridayEvents friday,
    WorkspaceArtifacts artifacts
  ) {
    this.friday = friday;
    this.artifacts = artifacts;
    this.specs = specs;
    this.store = store;
  }

  @PostMapping("/import")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ObjectNode load(@RequestBody Map<String, String> body, Principal p) {
    return artifacts.importSource(body.getOrDefault("source", ""),p.getName());
  }

  @PostMapping("/lint")
  public Object lint(@RequestBody Map<String, String> body) {
    return specs.lint(specs.parse(body.getOrDefault("source", "")));
  }

  @PostMapping("/diff")
  public Object diff(@RequestBody ObjectNode body) {
    return specs.diff(body.path("before"), body.path("after"));
  }

  private void revision(ObjectNode spec,ObjectNode body) {
    if(!body.path("expectedRevision").canConvertToInt() || body.path("expectedRevision").asInt()!=spec.path("revision").asInt()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Contract changed. Reload the selected API before this action.");
  }
  @PostMapping("/{id}/collection")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object collection(@PathVariable String id,@RequestBody ObjectNode body,Principal p) {
    var spec=store.get("specs",id);revision(spec,body);return artifacts.collection(spec,p.getName());
  }
  @PostMapping("/{id}/draft")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public Object draft(@PathVariable String id,@RequestBody ObjectNode body,Principal p) {
    var spec=store.get("specs",id);revision(spec,body);if(spec.path("status").asText().equals("DRAFT"))return spec;
    spec.put("status","DRAFT");spec.remove(List.of("reviewer","reviewRequestedBy"));return store.save("specs",spec,p.getName());
  }
  @PostMapping("/{id}/approval")
  @PreAuthorize("hasAnyRole('ADMIN','REVIEWER','DEVELOPER')")
  public Object approval(@PathVariable String id,@RequestBody ObjectNode body,org.springframework.security.core.Authentication auth) {
    var spec=store.get("specs",id);revision(spec,body);String status=body.path("status").asText("IN_REVIEW");
    if(!Set.of("IN_REVIEW","APPROVED","REJECTED").contains(status))throw new IllegalArgumentException("Invalid approval status");
    boolean allowed=auth.getAuthorities().stream().anyMatch(r->r.getAuthority().equals("ROLE_ADMIN")||r.getAuthority().equals(status.equals("IN_REVIEW")?"ROLE_DEVELOPER":"ROLE_REVIEWER"));
    if(!allowed)throw new org.springframework.security.access.AccessDeniedException("Only an administrator or reviewer can approve/reject; developers can request review");
    String current=spec.path("status").asText("DRAFT");
    if(status.equals("IN_REVIEW")){if(current.equals("IN_REVIEW"))return spec;if(!Set.of("DRAFT","REJECTED").contains(current))throw new IllegalArgumentException("Return the contract to draft before requesting review");}
    else if(!current.equals("IN_REVIEW"))throw new IllegalArgumentException("Request review before approving or rejecting this contract");
    if(status.equals("APPROVED")&&specs.lint(specs.parse(spec.path("document").toString())).stream().anyMatch(f->f.get("severity").equals("ERROR")))throw new IllegalArgumentException("Fix lint errors before approval");
    spec.put("status",status);if(status.equals("IN_REVIEW"))spec.put("reviewRequestedBy",auth.getName());else spec.put("reviewer",auth.getName());
    var saved=store.save("specs",spec,auth.getName());if(status.equals("APPROVED"))friday.emit("specApproved",saved,auth.getName());return saved;
  }

  @GetMapping("/governance")
  public Object governance() {
    var results = new ArrayList<Map<String, Object>>();
    for (var s : store.list("specs")) {
      var f = specs.lint((ObjectNode) s.get("document"));
      results.add(
        Map.of(
          "id",
          s.path("id").asText(),
          "name",
          s.path("name").asText(),
          "score",
          Math.max(0, 100 - f.size() * 10),
          "findings",
          f
        )
      );
    }
    return results;
  }
}
