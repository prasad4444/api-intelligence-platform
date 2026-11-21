package com.apiintel.chat;

import com.apiintel.integrations.IntegrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Microsoft 365 Copilot adapter over Graph beta. Creates a conversation
 * (POST /beta/copilot/conversations), persists the id in provider_state, then
 * posts the message via chatOverStream (SSE) or /chat (sync). Requires a
 * delegated work/school token, an M365 Copilot license, and an Entra app
 * registration with delegated Sites.Read.All, Mail.Read, People.Read.All,
 * OnlineMeetingTranscript.Read.All, Chat.Read, ChannelMessage.Read.All,
 * ExternalItem.Read.All. Without a configured clientId the service surfaces
 * ENTRA_APP_REGISTRATION and offers the browser-backed studio alternative.
 */
@Component
public class M365CopilotAdapter implements ChatAdapter {

  public static final String DEFAULT_BASE = "https://graph.microsoft.com";

  private final IntegrationService integrations;

  public M365CopilotAdapter(IntegrationService integrations) {
    this.integrations = integrations;
  }

  @Override
  public String name() {
    return "m365-copilot";
  }

  @Override
  public ObjectNode capabilities(ObjectNode connection) {
    return ChatSupport.capabilities("chat", true, true, true, false);
  }

  @Override
  public Flux<ChatEvent> stream(ChatRequest request) {
    return Flux.create(sink -> {
      try {
        var runtime = request.connection().deepCopy();
        if (runtime.path("baseUrl").asText().isBlank()) runtime.put("baseUrl", DEFAULT_BASE);
        String conversationId = request.providerState().path("conversationId").asText("");
        if (conversationId.isBlank()) {
          JsonNode created = integrations.call(runtime, "POST", "/beta/copilot/conversations",
            ChatSupport.JSON.createObjectNode(), request.username());
          conversationId = created.path("id").asText("");
          if (conversationId.isBlank()) throw new IllegalArgumentException("Copilot did not return a conversation id");
          request.providerState().put("conversationId", conversationId);
        }
        var body = ChatSupport.JSON.createObjectNode();
        body.putObject("message").put("text", request.prompt());
        body.putObject("locationHint").put("timeZone", "UTC");
        if (runtime.path("copilotStream").asBoolean(true)) {
          String path = "/beta/copilot/conversations/" + conversationId + "/chatOverStream";
          integrations.stream(runtime, path, body).collectList().subscribe(lines -> {
            String last = lastAssistantText(ChatSupport.ssePayloads(lines));
            if (!last.isEmpty()) sink.next(ChatSupport.delta(last));
            sink.complete();
          }, sink::error);
        } else {
          JsonNode response = integrations.call(runtime, "POST",
            "/beta/copilot/conversations/" + conversationId + "/chat", body, request.username());
          String text = extractAssistantText(response);
          if (!text.isEmpty()) sink.next(ChatSupport.delta(text));
          sink.complete();
        }
      } catch (RuntimeException e) {
        sink.error(e);
      }
    });
  }

  private String lastAssistantText(java.util.List<String> payloads) {
    String last = "";
    for (String payload : payloads) {
      try {
        String text = extractAssistantText(ChatSupport.JSON.readTree(payload));
        if (!text.isEmpty()) last = text;
      } catch (Exception ignored) {
      }
    }
    return last;
  }

  static String extractAssistantText(JsonNode response) {
    JsonNode messages = response.path("messages");
    String last = "";
    if (messages.isArray()) {
      for (JsonNode message : messages) {
        String role = message.path("role").asText(message.path("author").asText(""));
        if (!role.isBlank() && !role.toLowerCase(java.util.Locale.ROOT).contains("assistant")
            && !role.toLowerCase(java.util.Locale.ROOT).contains("bot")) continue;
        if (message.path("text").isTextual()) last = message.path("text").asText();
      }
    }
    if (last.isEmpty() && response.path("text").isTextual()) last = response.path("text").asText();
    return last;
  }
}
