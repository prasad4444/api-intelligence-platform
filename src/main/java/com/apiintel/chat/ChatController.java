package com.apiintel.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@PreAuthorize("isAuthenticated()")
public class ChatController {

  private final ChatService chat;

  public ChatController(ChatService chat) {
    this.chat = chat;
  }

  @GetMapping("/providers")
  public List<ObjectNode> providers(Principal principal) {
    return chat.providers(principal.getName());
  }

  @GetMapping("/conversations")
  public List<Map<String, Object>> conversations(Principal principal) {
    return chat.conversations(principal.getName());
  }

  @PostMapping("/conversations")
  public ObjectNode create(@RequestBody(required = false) ObjectNode body, Principal principal) {
    return chat.createConversation(body == null ? empty() : body, principal.getName());
  }

  @PatchMapping("/conversations/{id}")
  public ObjectNode rename(@PathVariable String id, @RequestBody ObjectNode body, Principal principal) {
    return chat.renameConversation(id, body, principal.getName());
  }

  @DeleteMapping("/conversations/{id}")
  public ObjectNode delete(@PathVariable String id, Principal principal) {
    chat.deleteConversation(id, principal.getName());
    return empty().put("deleted", true);
  }

  @GetMapping("/conversations/{id}/messages")
  public List<Map<String, Object>> messages(@PathVariable String id, Principal principal) {
    return chat.messages(id, principal.getName());
  }

  @PostMapping("/conversations/{id}/preview")
  public ObjectNode preview(@PathVariable String id, @RequestBody ObjectNode body, Principal principal) {
    return chat.preview(id, body, principal.getName());
  }

  @PostMapping(path = "/conversations/{id}/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<JsonNode>> send(@PathVariable String id, @RequestBody ObjectNode body,
      Principal principal) {
    return chat.send(id, body, principal.getName());
  }

  @PostMapping("/conversations/{id}/cancel")
  public ObjectNode cancel(@PathVariable String id, Principal principal) {
    return chat.cancel(id, principal.getName());
  }

  private static ObjectNode empty() {
    return ChatSupport.JSON.createObjectNode();
  }
}
