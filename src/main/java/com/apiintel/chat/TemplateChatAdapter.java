package com.apiintel.chat;

import com.apiintel.integrations.AiChat;
import com.apiintel.integrations.IntegrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Default adapter for template-configured connectors (Stylus, custom, company
 * endpoints and connectors inferred by Connection Studio), plus the built-in
 * openai/azure/anthropic/ollama providers handled by IntegrationService.
 * Multi-step captured protocols run through {@link MultiStepAdapter}.
 */
@Component
public class TemplateChatAdapter implements ChatAdapter {

  private final IntegrationService integrations;
  private final MultiStepAdapter multiStep;

  public TemplateChatAdapter(IntegrationService integrations, MultiStepAdapter multiStep) {
    this.integrations = integrations;
    this.multiStep = multiStep;
  }

  @Override
  public String name() {
    return "template";
  }

  @Override
  public ObjectNode capabilities(ObjectNode connection) {
    boolean sse = "sse".equals(connection.path("aiResponseFormat").asText());
    return ChatSupport.capabilities("chat", sse, true, true, false);
  }

  @Override
  public Flux<ChatEvent> stream(ChatRequest request) {
    var connection = request.connection();
    if (connection.path("steps").isArray() && connection.path("steps").size() > 0) {
      return multiStep.stream(request);
    }
    String prompt = withHistory(connection, request.prompt(), request.history());
    if (connection.path("aiChatEnabled").asBoolean()) {
      if ("sse".equals(connection.path("aiResponseFormat").asText())) {
        var payload = AiChat.request(connection, prompt);
        return Flux.create(sink -> {
          try {
            integrations.stream(connection, connection.path("chatPath").asText(), payload)
              .collectList()
              .subscribe(lines -> {
                var pointer = ChatSupport.pointer(connection.path("aiResponsePointer").asText());
                for (String ssePayload : ChatSupport.ssePayloads(lines)) {
                  if (request.cancelled().getAsBoolean()) break;
                  String text = ChatSupport.sseDelta(ssePayload, pointer);
                  if (!text.isEmpty()) sink.next(ChatSupport.delta(text));
                }
                sink.complete();
              }, sink::error);
          } catch (RuntimeException e) {
            sink.error(e);
          }
        });
      }
      return Flux.defer(() -> {
        var payload = AiChat.request(connection, prompt);
        JsonNode response = integrations.callAi(connection, payload, request.username());
        var selected = AiChat.response(connection, response);
        return Flux.just(ChatSupport.delta(selected.isTextual() ? selected.asText() : selected.toString()));
      });
    }
    // Built-in providers (openai/azure/anthropic/ollama) go through IntegrationService.chat.
    return Flux.defer(() -> {
      JsonNode result = integrations.chat(connection, prompt, request.username());
      return Flux.just(ChatSupport.delta(extractText(result)));
    });
  }

  static String withHistory(ObjectNode connection, String prompt, java.util.List<ObjectNode> history) {
    if (history == null || history.isEmpty()) return prompt;
    var transcript = new StringBuilder();
    for (ObjectNode turn : history) {
      String role = turn.path("role").asText("user");
      String content = turn.path("content").asText("");
      if (content.isBlank()) continue;
      transcript.append(role.equals("assistant") ? "Assistant: " : "User: ")
        .append(content).append("\n");
    }
    if (transcript.isEmpty()) return prompt;
    return "Conversation so far:\n" + transcript + "\nUser: " + prompt;
  }

  static String extractText(JsonNode result) {
    JsonNode response = result.has("response") ? result.path("response") : result;
    for (String pointer : new String[]{"/choices/0/message/content", "/choices/0/text", "/content/0/text", "/response", "/output"}) {
      var node = response.at(pointer);
      if (node.isTextual()) return node.asText();
    }
    return response.isTextual() ? response.asText() : response.toString();
  }
}
