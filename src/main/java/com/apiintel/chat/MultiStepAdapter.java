package com.apiintel.chat;

import com.apiintel.integrations.IntegrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Executes captured multi-request chat protocols declared on the connection as
 * {@code steps[]}: each step is {method, path, template?|body?, capture?:{name:pointer}}
 * and may reference {@code {{prompt}}}, {@code {{conversationId}}} and any value
 * captured from a previous step's response via {@code {{name}}}.
 */
@Component
public class MultiStepAdapter implements ChatAdapter {

  private final IntegrationService integrations;

  public MultiStepAdapter(IntegrationService integrations) {
    this.integrations = integrations;
  }

  @Override
  public String name() {
    return "multi-step";
  }

  @Override
  public ObjectNode capabilities(ObjectNode connection) {
    boolean sse = "sse".equals(connection.path("aiResponseFormat").asText());
    return ChatSupport.capabilities("chat", sse, true, true, false);
  }

  @Override
  public Flux<ChatEvent> stream(ChatRequest request) {
    return Flux.create(sink -> {
      try {
        var connection = request.connection();
        var variables = ChatSupport.JSON.createObjectNode();
        variables.put("prompt", request.prompt());
        String conversationId = request.providerState().path("conversationId").asText("");
        if (!conversationId.isBlank()) variables.put("conversationId", conversationId);
        JsonNode last = null;
        for (JsonNode stepNode : connection.path("steps")) {
          if (request.cancelled().getAsBoolean()) break;
          var step = (ObjectNode) stepNode;
          String method = step.path("method").asText("POST").toUpperCase(java.util.Locale.ROOT);
          String path = substitute(step.path("path").asText(""), variables);
          JsonNode body = step.has("template") ? substitute(step.path("template"), variables)
            : step.has("body") ? substitute(step.path("body"), variables) : null;
          last = integrations.call(connection, method, path, body, request.username());
          if (step.has("capture") && step.path("capture").isObject()) {
            var fields = step.path("capture").fields();
            while (fields.hasNext()) {
              var field = fields.next();
              var value = last.at(ChatSupport.pointer(field.getValue().asText()));
              if (!value.isMissingNode() && !value.isNull()) variables.set(field.getKey(), value);
            }
            if (variables.hasNonNull("conversationId")) {
              request.providerState().put("conversationId", variables.path("conversationId").asText());
            }
          }
        }
        var pointer = ChatSupport.pointer(connection.path("aiResponsePointer").asText());
        if ("sse".equals(connection.path("aiResponseFormat").asText()) && last != null && last.isTextual()) {
          for (String payload : ChatSupport.ssePayloads(java.util.List.of(last.asText()))) {
            String text = ChatSupport.sseDelta(payload, pointer);
            if (!text.isEmpty()) sink.next(ChatSupport.delta(text));
          }
        } else {
          JsonNode selected = last == null ? null : pointer.matches() ? last : last.at(pointer);
          if (selected != null && !selected.isMissingNode() && !selected.isNull()) {
            sink.next(ChatSupport.delta(selected.isTextual() ? selected.asText() : selected.toString()));
          }
        }
        sink.complete();
      } catch (RuntimeException e) {
        sink.error(e);
      }
    });
  }

  static JsonNode substitute(JsonNode node, ObjectNode variables) {
    if (node.isTextual()) {
      String text = node.asText();
      var fields = variables.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        text = text.replace("{{" + field.getKey() + "}}", field.getValue().asText());
      }
      return TextNode.valueOf(text);
    }
    if (node.isObject()) {
      var out = ChatSupport.JSON.createObjectNode();
      node.fields().forEachRemaining(f -> out.set(f.getKey(), substitute(f.getValue(), variables)));
      return out;
    }
    if (node.isArray()) {
      var out = ChatSupport.JSON.createArrayNode();
      node.forEach(child -> out.add(substitute(child, variables)));
      return out;
    }
    return node;
  }

  static String substitute(String text, ObjectNode variables) {
    var fields = variables.fields();
    while (fields.hasNext()) {
      var field = fields.next();
      text = text.replace("{{" + field.getKey() + "}}", field.getValue().asText());
    }
    return text;
  }
}
