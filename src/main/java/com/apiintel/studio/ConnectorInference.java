package com.apiintel.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure inference over studio captures: find the request whose body carries the
 * user's demo prompt, turn it into an aiRequestTemplate (prompt → {{prompt}}),
 * detect conversation-id fields, CSRF headers, signed/device-bound params
 * (→ replayMode browser), the response format (SSE vs JSON) and the best
 * aiResponsePointer. Multi-request protocols yield ordered {@code steps[]}.
 */
public final class ConnectorInference {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern CONVERSATION_ID =
    Pattern.compile("(?i)^(.*(?:conversation|thread|chat|session|dialog).*)id$|^id$");
  private static final Set<String> CSRF_HEADERS = Set.of(
    "x-csrf-token", "x-xsrf-token", "x-csrftoken", "requestverificationtoken",
    "x-request-verification-token", "csrf-token", "xsrf-token");
  private static final Pattern SIGNED_PARAM = Pattern.compile(
    "(?i)^(signature|sig|nonce|timestamp|x-ms-.*|sec-.*|device-.*|x-device.*|x-request-id|client-request-id)$");
  private static final List<String> RESPONSE_POINTERS = List.of(
    "/choices/0/message/content", "/choices/0/delta/content", "/choices/0/text",
    "/message/content/parts/0", "/message/content", "/content/parts/0",
    "/text", "/content", "/answer", "/response", "/output", "/messages/0/text");

  private ConnectorInference() {}

  /**
   * @param captures captured requests (method,url,headers,body,status,contentType,responsePreview)
   * @param promptMarker the demo prompt the user typed into the provider UI
   */
  public static ObjectNode infer(List<ObjectNode> captures, String promptMarker) {
    var result = JSON.createObjectNode();
    var notes = result.putArray("notes");
    ObjectNode chatCapture = null;
    if (promptMarker != null && !promptMarker.isBlank()) {
      for (ObjectNode capture : captures) {
        String body = capture.path("body").asText(capture.path("bodyPreview").asText(""));
        if (body.contains(promptMarker)) {
          chatCapture = capture;
          break;
        }
      }
    }
    if (chatCapture == null && !captures.isEmpty()) {
      // Fall back to the last POST xhr/fetch — most chat apps send one POST per message.
      for (int i = captures.size() - 1; i >= 0; i--) {
        if ("POST".equalsIgnoreCase(captures.get(i).path("method").asText())) {
          chatCapture = captures.get(i);
          notes.add("Prompt marker not found; using the last captured POST as the chat request");
          break;
        }
      }
    }
    if (chatCapture == null) {
      result.put("error", "No captured request contains the demo prompt — record a chat send first");
      return result;
    }
    var parsed = parseUrl(chatCapture.path("url").asText());
    result.put("chatPath", parsed.path());
    result.put("aiMethod", chatCapture.path("method").asText("POST"));

    JsonNode bodyJson = tryJson(chatCapture.path("body").asText(chatCapture.path("bodyPreview").asText("")));
    var template = bodyJson != null && bodyJson.isObject() ? (ObjectNode) bodyJson.deepCopy()
      : JSON.createObjectNode().set("message", JSON.getNodeFactory().textNode(promptMarker == null ? "" : promptMarker));
    boolean[] markerReplaced = {false};
    JsonNode substituted = substituteMarker(template, promptMarker, markerReplaced);
    var conversationIds = findConversationIds(substituted);
    for (String field : conversationIds) {
      replaceField(substituted, field, "{{conversationId}}");
    }
    // If no conversation id in the request body, look back at prior responses.
    var steps = result.putArray("steps");
    if (conversationIds.isEmpty()) {
      for (ObjectNode capture : captures) {
        if (capture == chatCapture) break;
        String preview = capture.path("responsePreview").asText("");
        JsonNode response = tryJson(preview);
        if (response == null || !response.isObject()) continue;
        for (String field : findConversationIds(response)) {
          var step = steps.addObject();
          step.put("method", capture.path("method").asText("GET"));
          step.put("path", parseUrl(capture.path("url").asText()).path());
          if (capture.has("body") && !capture.path("body").asText().isBlank()
              && capture.path("method").asText().equalsIgnoreCase("POST")) {
            step.set("template", tryJson(capture.path("body").asText()));
          }
          step.putObject("capture").put("conversationId", "/" + field.replace(".", "/"));
          notes.add("Detected a preceding request that returns the conversation id: " + capture.path("url").asText());
          break;
        }
      }
    }
    var chatStep = steps.addObject();
    chatStep.put("method", chatCapture.path("method").asText("POST"));
    chatStep.put("path", parsed.path());
    chatStep.set("template", substituted);
    result.set("aiRequestTemplate", substituted);

    // Response format + pointer suggestion.
    boolean sse = chatCapture.path("contentType").asText("").contains("event-stream")
      || chatCapture.path("sse").asBoolean(false);
    result.put("aiResponseFormat", sse ? "sse" : "json");
    JsonNode preview = tryJson(chatCapture.path("responsePreview").asText(""));
    if (sse) {
      for (String payload : ssePayloads(chatCapture.path("responsePreview").asText(""))) {
        JsonNode event = tryJson(payload);
        if (event != null) { preview = event; break; }
      }
    }
    result.put("aiResponsePointer", suggestPointer(preview));

    // Header analysis: CSRF and signed/device-bound params.
    var csrf = result.putArray("csrfHeaders");
    var signed = result.putArray("signedParams");
    var headers = chatCapture.path("headers");
    headers.fields().forEachRemaining(h -> {
      String name = h.getKey().toLowerCase(Locale.ROOT);
      if (CSRF_HEADERS.contains(name)) csrf.add(name);
      if (SIGNED_PARAM.matcher(name).matches() || name.startsWith("x-ms-") || name.startsWith("sec-")) {
        signed.add(name);
      }
    });
    for (String param : parsed.query().split("&")) {
      String key = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
      if (SIGNED_PARAM.matcher(key).matches()) signed.add(key);
    }
    result.put("replayMode", signed.size() > 0 ? "browser" : "http");
    var captureIds = result.putArray("captureIds");
    if (chatCapture.hasNonNull("id")) captureIds.add(chatCapture.path("id").asText());
    if (!markerReplaced[0] && promptMarker != null && !promptMarker.isBlank()) {
      notes.add("Could not locate the prompt inside the request body template — verify {{prompt}} placement");
    }
    return result;
  }

  static List<String> ssePayloads(String raw) {
    var out = new ArrayList<String>();
    for (String event : raw.split("\\r?\\n\\r?\\n")) {
      var data = new StringBuilder();
      for (String line : event.split("\\r?\\n")) {
        if (line.startsWith("data:")) {
          if (data.length() > 0) data.append('\n');
          data.append(line.substring(5).replaceFirst("^ ", ""));
        }
      }
      if (data.length() > 0 && !data.toString().equals("[DONE]")) out.add(data.toString());
    }
    return out;
  }

  private static JsonNode substituteMarker(JsonNode node, String marker, boolean[] done) {
    if (marker == null || marker.isBlank()) return node;
    if (node.isTextual()) {
      String text = node.asText();
      if (text.contains(marker)) {
        done[0] = true;
        return JSON.getNodeFactory().textNode(text.replace(marker, "{{prompt}}"));
      }
      return node;
    }
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var f = fields.next();
        f.setValue(substituteMarker(f.getValue(), marker, done));
      }
      return node;
    }
    if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        var child = substituteMarker(node.get(i), marker, done);
        if (child != node.get(i)) ((com.fasterxml.jackson.databind.node.ArrayNode) node).set(i, child);
      }
    }
    return node;
  }

  private static List<String> findConversationIds(JsonNode node) {
    var found = new ArrayList<String>();
    findConversationIds(node, "", found);
    return found;
  }

  private static void findConversationIds(JsonNode node, String prefix, List<String> out) {
    if (!node.isObject()) return;
    node.fields().forEachRemaining(f -> {
      String key = prefix.isEmpty() ? f.getKey() : prefix + "." + f.getKey();
      if (CONVERSATION_ID.matcher(f.getKey()).matches() && f.getValue().isTextual()
          && !f.getValue().asText().isBlank() && !f.getValue().asText().equals("{{conversationId}}")
          && !f.getValue().asText().equals("{{prompt}}")) {
        out.add(key);
      }
      if (f.getValue().isObject()) findConversationIds(f.getValue(), key, out);
    });
  }

  private static void replaceField(JsonNode node, String dotted, String value) {
    String[] parts = dotted.split("\\.");
    JsonNode current = node;
    for (int i = 0; i < parts.length - 1; i++) current = current.path(parts[i]);
    if (current instanceof ObjectNode obj) obj.put(parts[parts.length - 1], value);
  }

  private static String suggestPointer(JsonNode preview) {
    if (preview == null) return "";
    for (String pointer : RESPONSE_POINTERS) {
      if (preview.at(pointer).isTextual()) return pointer;
    }
    String[] best = {"", "0"};
    longestString(preview, "", best);
    return best[0];
  }

  private static void longestString(JsonNode node, String path, String[] best) {
    if (node.isTextual() && node.asText().length() > Integer.parseInt(best[1])
        && node.asText().length() > 2) {
      best[0] = path;
      best[1] = String.valueOf(node.asText().length());
      return;
    }
    if (node.isObject()) {
      node.fields().forEachRemaining(f -> longestString(f.getValue(), path + "/" + f.getKey(), best));
    } else if (node.isArray()) {
      for (int i = 0; i < node.size() && i < 5; i++) longestString(node.get(i), path + "/" + i, best);
    }
  }

  private record ParsedUrl(String path, String query) {}

  private static ParsedUrl parseUrl(String url) {
    try {
      var uri = java.net.URI.create(url);
      return new ParsedUrl(uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath(),
        uri.getRawQuery() == null ? "" : uri.getRawQuery());
    } catch (Exception e) {
      return new ParsedUrl("/", "");
    }
  }

  private static JsonNode tryJson(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      return JSON.readTree(text);
    } catch (Exception e) {
      return null;
    }
  }
}
