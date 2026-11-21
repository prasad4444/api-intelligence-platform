package com.apiintel.chat;

import com.apiintel.studio.BrowserLauncher;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Executes a captured chat request inside the provider's real web app using
 * the persistent per-connection browser profile (page.evaluate(fetch)). Used
 * when the captured protocol is device/session-bound (replayMode=browser).
 */
@Component
public class BrowserReplayAdapter implements ChatAdapter {

  private final BrowserLauncher launcher;

  public BrowserReplayAdapter(BrowserLauncher launcher) {
    this.launcher = launcher;
  }

  @Override
  public String name() {
    return "browser-replay";
  }

  @Override
  public ObjectNode capabilities(ObjectNode connection) {
    return ChatSupport.capabilities("chat", false, false, true, true);
  }

  @Override
  public Flux<ChatEvent> stream(ChatRequest request) {
    return Flux.create(sink -> {
      try {
        var connection = request.connection();
        var body = connection.has("aiRequestTemplate")
          ? MultiStepAdapter.substitute(connection.path("aiRequestTemplate"),
              ChatSupport.JSON.createObjectNode().put("prompt", request.prompt()))
          : ChatSupport.JSON.createObjectNode().put("prompt", request.prompt());
        String path = connection.path("chatPath").asText("");
        try (var handle = launcher.open(new BrowserLauncher.OpenRequest(
            connection, true, opened -> {}, capture -> {}, action -> {}, () -> request.cancelled().getAsBoolean()))) {
          var script = new StringBuilder();
          script.append("fetch(").append(ChatSupport.JSON.writeValueAsString(path)).append(",")
            .append("{method:").append(ChatSupport.JSON.writeValueAsString(connection.path("aiMethod").asText("POST")))
            .append(",headers:{'Content-Type':'application/json'},credentials:'include',body:")
            .append(ChatSupport.JSON.writeValueAsString(body.toString())).append("})")
            .append(".then(r=>r.text()).then(t=>t).catch(e=>String(e))");
          Object result = handle.evaluate(script.toString());
          String text = result == null ? "" : String.valueOf(result);
          if (text.isBlank() || text.toLowerCase(java.util.Locale.ROOT).contains("login")
              || text.toLowerCase(java.util.Locale.ROOT).contains("unauthorized")) {
            sink.error(new IllegalArgumentException("Browser session expired — reconnect required"));
            return;
          }
          var pointer = ChatSupport.pointer(connection.path("aiResponsePointer").asText());
          String delta;
          try {
            var parsed = ChatSupport.JSON.readTree(text);
            var selected = pointer.matches() ? parsed : parsed.at(pointer);
            delta = selected.isTextual() ? selected.asText() : selected.toString();
          } catch (Exception e) {
            delta = text;
          }
          if (!delta.isBlank()) sink.next(ChatSupport.delta(delta));
          sink.complete();
        }
      } catch (RuntimeException e) {
        sink.error(e);
      } catch (Exception e) {
        sink.error(new IllegalArgumentException("Browser replay failed: " + e.getMessage()));
      }
    });
  }
}
