package com.apiintel.notify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotifyController {

  private final NotifyService notify;

  public NotifyController(NotifyService notify) {
    this.notify = notify;
  }

  @GetMapping("/api/notify/destinations")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> destinations(Principal p) {
    return notify.destinations(p.getName());
  }

  @PostMapping("/api/notify/destinations")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode save(@RequestBody ObjectNode input, Principal p) {
    return notify.saveDestination(input, p.getName());
  }

  @PostMapping("/api/notify/destinations/{id}/preview")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode preview(@PathVariable String id, Principal p) {
    return notify.previewDestination(id, p.getName());
  }

  @PostMapping("/api/notify/destinations/{id}/enable")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode enable(@PathVariable String id, @RequestBody(required = false) ObjectNode input,
                           Principal p) {
    return notify.enableDestination(id, input == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : input, p.getName());
  }

  @PostMapping("/api/notify/destinations/{id}/disable")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode disable(@PathVariable String id, Principal p) {
    return notify.disableDestination(id, p.getName());
  }

  @GetMapping("/api/notify/outbox")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<Map<String, Object>> outbox(Principal p) {
    return notify.outboxHistory(p.getName());
  }

  @GetMapping("/api/notifications")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<Map<String, Object>> notifications(Principal p) {
    return notify.notifications(p.getName());
  }

  @PostMapping("/api/notifications/{id}/read")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public void read(@PathVariable String id, Principal p) {
    notify.markRead(id, p.getName());
  }
}
