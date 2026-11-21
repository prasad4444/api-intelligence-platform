package com.apiintel.chat;

import com.apiintel.integrations.IntegrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Devin task-mode adapter. Starts a session (POST /v1/sessions), keeps the id
 * in provider_state for follow-ups (POST /v1/sessions/{id}/message) and polls
 * GET /v1/session/{id} emitting task events plus new Devin messages as deltas.
 * Bearer Devin API key; ACU usage is billed to the org. Poll cadence is
 * tunable per connection via pollIntervalMs / maxPolls (defaults 5s / 120).
 */
@Component
public class DevinAdapter implements ChatAdapter {

  public static final String DEFAULT_BASE = "https://api.devin.ai";
  private static final Set<String> TERMINAL = Set.of("finished", "blocked", "stopped", "expired");

  private final IntegrationService integrations;

  public DevinAdapter(IntegrationService integrations) {
    this.integrations = integrations;
  }

  @Override
  public String name() {
    return "devin";
  }

  @Override
  public ObjectNode capabilities(ObjectNode connection) {
    return ChatSupport.capabilities("task", false, true, true, false);
  }

  @Override
  public Flux<ChatEvent> stream(ChatRequest request) {
    return Flux.create(sink -> {
      try {
        var runtime = request.connection().deepCopy();
        if (runtime.path("baseUrl").asText().isBlank()) runtime.put("baseUrl", DEFAULT_BASE);
        String sessionId = request.providerState().path("sessionId").asText("");
        String sessionUrl = request.providerState().path("url").asText("");
        if (sessionId.isBlank()) {
          var body = ChatSupport.JSON.createObjectNode();
          body.put("prompt", request.prompt());
          body.put("idempotent", false);
          JsonNode created = integrations.call(runtime, "POST", "/v1/sessions", body, request.username());
          sessionId = created.path("session_id").asText("");
          sessionUrl = created.path("url").asText("");
          if (sessionId.isBlank()) throw new IllegalArgumentException("Devin did not return a session id");
          request.providerState().put("sessionId", sessionId);
          request.providerState().put("url", sessionUrl);
        } else {
          var body = ChatSupport.JSON.createObjectNode();
          body.put("message", request.prompt());
          integrations.call(runtime, "POST", "/v1/sessions/" + sessionId + "/message", body, request.username());
        }
        sink.next(ChatSupport.task(sessionId, "running", sessionUrl));
        long interval = Math.max(50, runtime.path("pollIntervalMs").asLong(5000));
        int maxPolls = Math.max(1, runtime.path("maxPolls").asInt(120));
        int delivered = request.providerState().path("delivered").asInt(0);
        for (int poll = 0; poll < maxPolls; poll++) {
          if (request.cancelled().getAsBoolean()) {
            sink.complete();
            return;
          }
          Thread.sleep(poll == 0 ? Math.min(interval, 200) : interval);
          JsonNode status = integrations.call(runtime, "GET", "/v1/session/" + sessionId, null, request.username());
          String state = status.path("status_enum").asText(status.path("status").asText("")).toLowerCase(java.util.Locale.ROOT);
          JsonNode messages = status.path("messages");
          if (messages.isArray()) {
            for (int i = delivered; i < messages.size(); i++) {
              var message = messages.get(i);
              String type = message.path("type").asText(message.path("role").asText(""));
              if (!type.toLowerCase(java.util.Locale.ROOT).contains("devin")
                  && !type.toLowerCase(java.util.Locale.ROOT).contains("assistant")) continue;
              String text = message.path("message").asText(message.path("text").asText(""));
              if (!text.isBlank()) sink.next(ChatSupport.delta(text));
              delivered = i + 1;
            }
            request.providerState().put("delivered", delivered);
          }
          sink.next(ChatSupport.task(sessionId, state.isBlank() ? "running" : state, sessionUrl));
          if (TERMINAL.contains(state)) break;
        }
        sink.complete();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        sink.complete();
      } catch (RuntimeException e) {
        sink.error(e);
      }
    });
  }
}
