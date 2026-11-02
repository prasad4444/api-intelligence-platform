package com.apiintel.integrations;

import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

  private final IntegrationService service;
  private final Vault vault;
  private final RecordStore store;

  public IntegrationController(IntegrationService s, Vault v, RecordStore r) {
    service = s;
    vault = v;
    store = r;
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public Object save(@RequestBody ObjectNode data, Principal p) {
    return service.save(data, p.getName());
  }

  @GetMapping("/secrets")
  @PreAuthorize("hasRole('ADMIN')")
  public Object secrets() {
    return vault.names();
  }

  @PutMapping("/secrets/{name}")
  @PreAuthorize("hasRole('ADMIN')")
  public void secret(
    @PathVariable String name,
    @RequestBody Map<String, String> data,
    Principal p
  ) {
    vault.put(name, data.getOrDefault("value", ""));
    store.audit(p.getName(), "secret:rotate", name);
  }

  @DeleteMapping("/secrets/{name}")
  @PreAuthorize("hasRole('ADMIN')")
  public void remove(@PathVariable String name, Principal p) {
    vault.delete(name);
    store.audit(p.getName(), "secret:delete", name);
  }

  @PostMapping("/{id}/read")
  public Object read(
    @PathVariable String id,
    @RequestBody Map<String, String> data,
    Principal p
  ) {
    service.authorize(id);
    return service.call(
      service.config(id),
      "GET",
      data.getOrDefault("path", ""),
      null,
      p.getName()
    );
  }

  @PostMapping("/{id}/proposals")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object propose(
    @PathVariable String id,
    @RequestBody ObjectNode action,
    Principal p
  ) {
    service.authorize(id);
    return service.propose(id, action, p.getName());
  }

  @PostMapping("/approvals/{id}/approve")
  @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
  public Object approve(@PathVariable String id, Principal p) {
    return service.approve(id, p.getName());
  }

  @PostMapping("/approvals/{id}/execute")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object execute(@PathVariable String id, Principal p) {
    service.authorize(store.get("events", id).path("integrationId").asText());
    return service.execute(id, p.getName());
  }

  @PostMapping(
    value = "/approvals/{id}/stream",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE
  )
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Flux<String> streamApproval(@PathVariable String id, Principal p) {
    service.authorize(store.get("events", id).path("integrationId").asText());
    return service.executeStream(id, p.getName());
  }

  @PostMapping(
    value = "/{id}/friday/chat",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE
  )
  public Flux<String> stream(
    @PathVariable String id,
    @RequestBody ObjectNode data,
    Principal p
  ) {
    service.authorize(id);
    var c = service.config(id);
    if (
      !c.path("provider").asText().equals("friday")
    ) throw new IllegalArgumentException("Select a Friday connection");
    String ws = data.path("workspaceId").asText();
    if (!ws.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException(
      "Invalid workspace id"
    );
    store.audit(p.getName(), "friday:chat", id);
    return service.stream(
      c,
      "/api/workspaces/" + ws + "/chat",
      data.path("body")
    );
  }

  @PostMapping("/{id}/ai")
  public Object ai(
    @PathVariable String id,
    @RequestBody ObjectNode data,
    Principal p
  ) {
    service.authorize(id);
    var c = service.config(id);
    String provider = c.path("provider").asText();
    String prompt = data.path("prompt").asText();
    if (prompt.length() > 20000) throw new IllegalArgumentException(
      "Prompt is too large"
    );
    var b = store.parse("{}");
    b.put("model", c.path("model").asText(""));
    String path;
    switch (provider) {
      case "openai", "azure" -> {
        var m = b.putArray("messages").addObject();
        m.put("role", "user");
        m.put("content", prompt);
        path = c.path("chatPath").asText("/v1/chat/completions");
      }
      case "anthropic" -> {
        b.put("max_tokens", 1024);
        var m = b.putArray("messages").addObject();
        m.put("role", "user");
        m.put("content", prompt);
        path = "/v1/messages";
      }
      case "ollama" -> {
        b.put("prompt", prompt);
        b.put("stream", false);
        path = "/api/generate";
      }
      default -> throw new IllegalArgumentException(
        "Use the connector action editor for this provider"
      );
    }
    return service.call(c, "POST", path, b, p.getName());
  }
}
