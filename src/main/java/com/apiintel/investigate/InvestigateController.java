package com.apiintel.investigate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/investigations")
public class InvestigateController {

  private final InvestigateService investigate;

  public InvestigateController(InvestigateService investigate) {
    this.investigate = investigate;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> list(Principal p) {
    return investigate.list(p.getName());
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode create(@RequestBody ObjectNode input, Principal p) {
    return investigate.create(input, p.getName());
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode get(@PathVariable String id, Principal p) {
    return investigate.get(id, p.getName());
  }

  @PostMapping("/{id}/preview")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode preview(@PathVariable String id, @RequestBody ObjectNode input, Principal p) {
    return investigate.preview(id, input, p.getName());
  }

  @PostMapping(value = "/{id}/send", produces = "text/event-stream")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Flux<ServerSentEvent<JsonNode>> send(@PathVariable String id, @RequestBody ObjectNode input,
                                            Principal p) {
    return investigate.send(id, input, p.getName());
  }

  @PostMapping("/{id}/jira-draft")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode jiraDraft(@PathVariable String id, @RequestBody(required = false) ObjectNode input,
                              Principal p) {
    return investigate.jiraDraft(id,
        input == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : input,
        p.getName());
  }

  @PostMapping("/{id}/resolve")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode resolve(@PathVariable String id, @RequestBody ObjectNode input, Principal p) {
    return investigate.resolve(id, input, p.getName());
  }
}
