package com.apiintel.splunk;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/splunk")
public class SplunkController {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;
  private final SplunkService splunk;

  public SplunkController(SplunkService splunk) {
    this.splunk = splunk;
  }

  @PostMapping("/{connectionId}/validate")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode validate(@PathVariable String connectionId, Principal p) {
    return splunk.validate(connectionId, p.getName());
  }

  @GetMapping("/{connectionId}/saved-searches")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ArrayNode savedSearches(@PathVariable String connectionId,
                                 @RequestParam(defaultValue = "50") int count, Principal p) {
    return splunk.savedSearches(connectionId, p.getName(), count);
  }

  @PostMapping("/{connectionId}/mapping-preview")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode mappingPreview(@PathVariable String connectionId,
                                   @RequestBody(required = false) ObjectNode input, Principal p) {
    return splunk.mappingPreview(connectionId, input == null ? J.objectNode() : input, p.getName());
  }

  @PostMapping("/{connectionId}/jobs")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode createJob(@PathVariable String connectionId,
                              @RequestBody ObjectNode input, Principal p) {
    return splunk.createJob(connectionId, input, p.getName());
  }

  @GetMapping("/jobs/{jobId}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode jobStatus(@PathVariable String jobId, Principal p) {
    return splunk.jobStatus(jobId, p.getName());
  }

  @GetMapping("/jobs/{jobId}/results")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode jobResults(@PathVariable String jobId,
                               @RequestParam(defaultValue = "0") long offset,
                               @RequestParam(defaultValue = "1000") long count, Principal p) {
    return splunk.jobResults(jobId, offset, count, p.getName());
  }

  @PostMapping("/jobs/{jobId}/cancel")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode cancelJob(@PathVariable String jobId, Principal p) {
    return splunk.cancelJob(jobId, p.getName());
  }

  @GetMapping("/templates")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public java.util.List<ObjectNode> templates(Principal p) {
    return splunk.templates(p.getName());
  }

  @PostMapping("/templates")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode saveTemplate(@RequestBody ObjectNode input, Principal p) {
    return splunk.saveTemplate(input, p.getName());
  }

  @DeleteMapping("/templates/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public void deleteTemplate(@PathVariable String id, Principal p) {
    splunk.deleteTemplate(id, p.getName());
  }

  @ExceptionHandler(SplunkClient.SplunkException.class)
  public ResponseEntity<ObjectNode> splunkError(SplunkClient.SplunkException e) {
    ObjectNode body = J.objectNode().put("code", e.code).put("message", e.getMessage());
    if ("SPLUNK_QUOTA".equals(e.code)) body.put("action", "RETRY");
    if ("SPLUNK_AUTH".equals(e.code)) body.put("action", "RECONNECT");
    HttpStatus status = switch (e.status) {
      case 401 -> HttpStatus.UNAUTHORIZED;
      case 403 -> HttpStatus.FORBIDDEN;
      case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
      case 404 -> HttpStatus.NOT_FOUND;
      default -> e.status >= 400 && e.status < 600
          ? HttpStatus.valueOf(e.status) : HttpStatus.BAD_GATEWAY;
    };
    return ResponseEntity.status(status).body(body);
  }
}
