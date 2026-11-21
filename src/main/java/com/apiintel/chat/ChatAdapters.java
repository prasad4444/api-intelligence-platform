package com.apiintel.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Static adapter registry. Adapter selection is derived from the connection,
 * never from UI input: github-copilot → openai-compatible, copilot → m365,
 * devin → devin, replayMode=browser → browser-replay, steps[] → multi-step,
 * otherwise → template.
 */
@Component
public class ChatAdapters {

  private final Map<String, ChatAdapter> byName;

  public ChatAdapters(List<ChatAdapter> adapters) {
    this.byName = adapters.stream().collect(Collectors.toMap(ChatAdapter::name, Function.identity()));
  }

  public ChatAdapter forConnection(ObjectNode connection) {
    if ("browser".equals(connection.path("replayMode").asText(""))) {
      return byName.get("browser-replay");
    }
    if (connection.path("steps").isArray() && connection.path("steps").size() > 0) {
      return byName.get("multi-step");
    }
    return switch (connection.path("provider").asText()) {
      case "github-copilot" -> byName.get("openai-compatible");
      case "copilot" -> byName.get("m365-copilot");
      case "devin" -> byName.get("devin");
      default -> byName.get("template");
    };
  }
}
