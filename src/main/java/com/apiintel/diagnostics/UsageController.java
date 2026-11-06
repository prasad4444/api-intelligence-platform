package com.apiintel.diagnostics;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/usage")
@PreAuthorize("hasRole('ADMIN')")
public class UsageController {
  private final UsageStore store;

  public UsageController(UsageStore store) { this.store = store; }

  @GetMapping
  public Map<String, Object> usage(@RequestParam(required = false) String requestId,
      @RequestParam(defaultValue = "0") long beforeId, @RequestParam(defaultValue = "100") int limit) {
    return Map.of("totals", store.totals(), "events", store.recent(requestId, beforeId, limit));
  }
}
