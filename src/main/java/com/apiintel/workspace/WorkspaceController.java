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
  private final RecordStore store;

  public WorkspaceController(
    SpecService specs,
    RecordStore store,
    com.apiintel.integrations.FridayEvents friday
  ) {
    this.friday = friday;
    this.specs = specs;
    this.store = store;
  }

  @PostMapping("/import")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ObjectNode load(@RequestBody Map<String, String> body, Principal p) {
    var spec = specs.parse(body.getOrDefault("source", ""));
    var data = store.parse("{}");
    data.put("name", spec.path("info").path("title").asText("API"));
    data.put("version", spec.path("info").path("version").asText("1.0"));
    data.put("status", "DRAFT");
    data.set("document", spec);
    return store.save("specs", data, p.getName());
  }

  @PostMapping("/lint")
  public Object lint(@RequestBody Map<String, String> body) {
    return specs.lint(specs.parse(body.getOrDefault("source", "")));
  }

  @PostMapping("/diff")
  public Object diff(@RequestBody ObjectNode body) {
    return specs.diff(body.path("before"), body.path("after"));
  }

  @PostMapping("/{id}/collection")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object collection(@PathVariable String id, Principal p) {
    var c = specs.collection(
      (ObjectNode) store.get("specs", id).get("document")
    );
    c.put("name", c.path("info").path("name").asText());
    return store.save("collections", c, p.getName());
  }

  @PostMapping("/{id}/approval")
  @PreAuthorize("hasAnyRole('ADMIN','REVIEWER','DEVELOPER')")
  public Object approval(
    @PathVariable String id,
    @RequestBody Map<String, String> body,
    Principal p
  ) {
    var s = store.get("specs", id);
    String status = body.getOrDefault("status", "IN_REVIEW");
    if (
      !Set.of("IN_REVIEW", "APPROVED", "REJECTED").contains(status)
    ) throw new IllegalArgumentException("Invalid approval status");
    if (
      status.equals("APPROVED") &&
      specs
        .lint((ObjectNode) s.get("document"))
        .stream()
        .anyMatch(f -> f.get("severity").equals("ERROR"))
    ) throw new IllegalArgumentException("Fix lint errors before approval");
    s.put("status", status);
    s.put("reviewer", p.getName());
    var saved = store.save("specs", s, p.getName());
    if (status.equals("APPROVED")) friday.emit(
      "specApproved",
      saved,
      p.getName()
    );
    return saved;
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
