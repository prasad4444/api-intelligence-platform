package com.apiintel.integrations;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

public final class AiChat {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PROMPT = "{{prompt}}";

  private AiChat() {}

  public static ObjectNode request(ObjectNode config, String prompt) {
    JsonNode template = config.path("aiRequestTemplate");
    if (!template.isObject() || template.toString().length() > 32000) {
      throw new IllegalArgumentException("Enter a JSON object request template of up to 32,000 characters");
    }
    if (!template.toString().contains(PROMPT)) {
      throw new IllegalArgumentException("Put {{prompt}} in the JSON field that receives the message");
    }
    String path = config.path("chatPath").asText();
    if (path.isBlank()) throw new IllegalArgumentException("Enter the full AI request URL");
    if (path.matches("(?i).*[?&][^=&]*(?:token|secret|password|api[_-]?key)[^=&]*=.*")) {
      throw new IllegalArgumentException("Store API credentials in the credential field, separate from the URL");
    }
    pointer(config);
    return (ObjectNode) substitute(template, prompt);
  }

  private static JsonNode substitute(JsonNode node, String prompt) {
    if (node.isTextual()) return TextNode.valueOf(node.asText().replace(PROMPT, prompt));
    if (node.isObject()) {
      var result = JSON.createObjectNode();
      node.fields().forEachRemaining(field -> result.set(field.getKey(), substitute(field.getValue(), prompt)));
      return result;
    }
    if (node.isArray()) {
      var result = JSON.createArrayNode();
      node.forEach(child -> result.add(substitute(child, prompt)));
      return result;
    }
    return node;
  }

  private static JsonPointer pointer(ObjectNode config) {
    return JsonPointer.compile(config.path("aiResponsePointer").asText().trim());
  }

  public static JsonNode response(ObjectNode config, JsonNode response) {
    var selector = pointer(config);
    if (config.path("aiResponseFormat").asText().equals("sse")) {
      if (!response.isTextual()) throw new IllegalArgumentException("Expected an SSE response. Check the response format");
      StringBuilder text = new StringBuilder();
      for (String event : response.asText().split("\\r?\\n\\r?\\n")) {
        StringBuilder data = new StringBuilder();
        for (String line : event.split("\\r?\\n")) {
          if (!line.startsWith("data:")) continue;
          if (!data.isEmpty()) data.append('\n');
          data.append(line.substring(5).replaceFirst("^ ", ""));
        }
        if (data.isEmpty() || data.toString().equals("[DONE]")) continue;
        try {
          var value = JSON.readTree(data.toString());
          var selected = selector.matches() ? value : value.at(selector);
          if (selected.isTextual()) text.append(selected.asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
          if (selector.matches()) text.append(data);
        }
      }
      if (text.isEmpty()) throw new IllegalArgumentException("The SSE response contained no matching text. Check its JSON pointer");
      return TextNode.valueOf(text.toString());
    }
    if (selector.matches()) return response;
    var selected = response.at(selector);
    if (selected.isMissingNode() || selected.isNull()) {
      throw new IllegalArgumentException("The response JSON pointer did not match a value");
    }
    return selected;
  }
}
