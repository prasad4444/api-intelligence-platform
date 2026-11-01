package com.apiintel.workspace;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.*;
import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

  private final RecordStore store;
  private final SpecService specs;

  public GovernanceController(RecordStore r, SpecService s) {
    store = r;
    specs = s;
  }

  @PostMapping("/rules")
  @PreAuthorize("hasRole('ADMIN')")
  public Object rules(@RequestBody ObjectNode rule, Principal p) {
    if (
      !Set.of(
        "summary",
        "operationId",
        "responses",
        "security",
        "version"
      ).contains(rule.path("check").asText())
    ) throw new IllegalArgumentException("Unknown check");
    if (
      !Set.of("ERROR", "WARNING").contains(rule.path("severity").asText())
    ) throw new IllegalArgumentException("Severity must be ERROR or WARNING");
    return store.save("rules", rule, p.getName());
  }

  @PostMapping("/evaluate")
  public Object evaluate(Principal p) {
    var run = store.parse("{}");
    var scores = run.putArray("scores");
    double total = 0;
    for (var s : store.list("specs")) {
      var findings = specs.lint((ObjectNode) s.path("document"));
      var score = scores.addObject();
      score.put("name", s.path("name").asText());
      score.put("specId", s.path("id").asText());
      int value = Math.max(0, 100 - findings.size() * 10);
      score.put("score", value);
      total += value;
    }
    run.put(
      "score",
      scores.isEmpty() ? 100 : Math.round(total / scores.size())
    );
    return store.save("governance-runs", run, p.getName());
  }
}
