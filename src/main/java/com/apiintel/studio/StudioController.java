package com.apiintel.studio;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/studio")
@PreAuthorize("isAuthenticated()")
public class StudioController {

  private final StudioService studio;

  public StudioController(StudioService studio) {
    this.studio = studio;
  }

  @PostMapping("/sessions")
  public ObjectNode start(@RequestBody ObjectNode body, Principal principal) {
    return studio.start(body, principal.getName());
  }

  @GetMapping("/sessions/{id}")
  public ObjectNode get(@PathVariable String id, Principal principal) {
    return studio.get(id, principal.getName());
  }

  @GetMapping(path = "/sessions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ObjectNode> events(@PathVariable String id, Principal principal) {
    return studio.events(id, principal.getName());
  }

  @GetMapping("/timeline")
  public List<Map<String, Object>> timeline(@RequestParam String connectionId, Principal principal) {
    return studio.timeline(connectionId, principal.getName());
  }

  @PostMapping("/sessions/{id}/signed-in")
  public ObjectNode signedIn(@PathVariable String id, Principal principal) {
    return studio.signedIn(id, principal.getName());
  }

  @PostMapping("/sessions/{id}/record/start")
  public ObjectNode recordStart(@PathVariable String id, @RequestBody(required = false) ObjectNode body,
      Principal principal) {
    return studio.recordStart(id, principal.getName(),
      body == null ? "" : body.path("promptMarker").asText(""));
  }

  @PostMapping("/sessions/{id}/record/stop")
  public ObjectNode recordStop(@PathVariable String id, Principal principal) {
    return studio.recordStop(id, principal.getName());
  }

  @PostMapping("/sessions/{id}/infer")
  public ObjectNode infer(@PathVariable String id, @RequestBody(required = false) ObjectNode body,
      Principal principal) {
    return studio.infer(id, body == null ? studioEmpty() : body, principal.getName());
  }

  @PostMapping("/sessions/{id}/validate")
  public ObjectNode validate(@PathVariable String id, @RequestBody(required = false) ObjectNode body,
      Principal principal) {
    return studio.validate(id, body == null ? studioEmpty() : body, principal.getName());
  }

  /** Sanitized HAR import: keeps xhr/fetch entries, strips secrets, reports removals. */
  @PostMapping(path = "/har", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ObjectNode importHar(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
      Principal principal) throws java.io.IOException {
    return studio.importHar(file.getBytes(), principal.getName());
  }

  /** Inference over imported/pasted captures (no browser session). */
  @PostMapping("/har/infer")
  public ObjectNode inferCaptures(@RequestBody ObjectNode body, Principal principal) {
    return studio.inferCaptures(body, principal.getName());
  }

  @PostMapping("/sessions/{id}/cancel")
  public ObjectNode cancel(@PathVariable String id, Principal principal) {
    return studio.cancel(id, principal.getName());
  }

  private static ObjectNode studioEmpty() {
    return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
  }
}
