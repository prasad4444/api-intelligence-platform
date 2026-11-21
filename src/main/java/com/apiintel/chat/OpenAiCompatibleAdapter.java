package com.apiintel.chat;

import com.apiintel.integrations.IntegrationService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * OpenAI-compatible chat completions adapter, used by github-copilot via
 * GitHub Models (POST {base}/chat/completions, stream:true, Bearer PAT with
 * models:read). A Copilot subscription itself exposes no public chat API;
 * GitHub Models runs on a free tier plus paid usage.
 */
@Component
public class OpenAiCompatibleAdapter implements ChatAdapter {

  public static final String DEFAULT_BASE = "https://models.github.ai/inference";
  public static final String DEFAULT_MODEL = "openai/gpt-4.1";

  private final IntegrationService integrations;

  public OpenAiCompatibleAdapter(IntegrationService integrations) {
    this.integrations = integrations;
  }

  @Override
  public String name() {
    return "openai-compatible";
  }

  @Override
  public ObjectNode capabilities(ObjectNode connection) {
    return ChatSupport.capabilities("chat", true, true, true, false);
  }

  @Override
  public Flux<ChatEvent> stream(ChatRequest request) {
    var runtime = request.connection().deepCopy();
    if (runtime.path("baseUrl").asText().isBlank()) runtime.put("baseUrl", DEFAULT_BASE);
    runtime.put("aiResponseFormat", "sse");
    var body = ChatSupport.JSON.createObjectNode();
    body.put("model", runtime.path("model").asText(DEFAULT_MODEL));
    body.put("stream", true);
    var messages = body.putArray("messages");
    if (request.history() != null) {
      for (ObjectNode turn : request.history()) {
        String role = turn.path("role").asText("user");
        if (!java.util.Set.of("user", "assistant", "system").contains(role)) continue;
        String content = turn.path("content").asText("");
        if (content.isBlank()) continue;
        messages.addObject().put("role", role).put("content", content);
      }
    }
    messages.addObject().put("role", "user").put("content", request.prompt());
    var pointer = ChatSupport.pointer("/choices/0/delta/content");
    return Flux.create(sink -> {
      try {
        integrations.stream(runtime, "/chat/completions", body)
          .collectList()
          .subscribe(lines -> {
            for (String payload : ChatSupport.ssePayloads(lines)) {
              if (request.cancelled().getAsBoolean()) break;
              String text = ChatSupport.sseDelta(payload, pointer);
              if (!text.isEmpty()) sink.next(ChatSupport.delta(text));
            }
            sink.complete();
          }, sink::error);
      } catch (RuntimeException e) {
        sink.error(e);
      }
    });
  }
}
