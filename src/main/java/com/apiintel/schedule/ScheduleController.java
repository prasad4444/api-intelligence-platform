package com.apiintel.schedule;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

  private final ScheduleService schedules;

  public ScheduleController(ScheduleService schedules) {
    this.schedules = schedules;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> list(Principal p) {
    return schedules.list(p.getName());
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode save(@RequestBody ObjectNode input, Principal p) {
    return schedules.save(input, p.getName());
  }

  @GetMapping("/preview")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ArrayNode preview(@RequestParam String kind, @RequestParam String expr,
                           @RequestParam(defaultValue = "UTC") String zone,
                           @RequestParam(defaultValue = "5") int count) {
    return schedules.preview(kind, expr, zone, count);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode get(@PathVariable String id, Principal p) {
    return schedules.get(id, p.getName());
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public void delete(@PathVariable String id, Principal p) {
    schedules.delete(id, p.getName());
  }

  @PostMapping("/{id}/pause")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode pause(@PathVariable String id, Principal p) {
    return schedules.pause(id, p.getName());
  }

  @PostMapping("/{id}/resume")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode resume(@PathVariable String id, Principal p) {
    return schedules.resume(id, p.getName());
  }

  @PostMapping("/{id}/run-now")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode runNow(@PathVariable String id, Principal p) {
    return schedules.runNow(id, p.getName());
  }

  @GetMapping("/{id}/runs")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> history(@PathVariable String id, Principal p) {
    return schedules.history(id, p.getName());
  }

  @PostMapping("/runs/{runId}/cancel")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode cancelRun(@PathVariable String runId, Principal p) {
    return schedules.cancelRun(runId, p.getName());
  }
}
