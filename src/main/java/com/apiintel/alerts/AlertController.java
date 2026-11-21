package com.apiintel.alerts;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

  private final AlertService alerts;

  public AlertController(AlertService alerts) {
    this.alerts = alerts;
  }

  @GetMapping("/rules")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> rules(Principal p) {
    return alerts.rules(p.getName());
  }

  @PostMapping("/rules")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode save(@RequestBody ObjectNode input, Principal p) {
    return alerts.saveRule(input, p.getName());
  }

  @DeleteMapping("/rules/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public void delete(@PathVariable String id, Principal p) {
    alerts.deleteRule(id, p.getName());
  }

  @PostMapping("/rules/{id}/backtest")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ArrayNode backtest(@PathVariable String id, Principal p) {
    return alerts.backtest(id, p.getName());
  }

  @GetMapping("/incidents")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> incidents(Principal p) {
    return alerts.incidents(p.getName());
  }

  @GetMapping("/incidents/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode incident(@PathVariable String id, Principal p) {
    return alerts.incident(id, p.getName());
  }
}
