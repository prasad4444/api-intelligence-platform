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

    // Pagination detected from the request query or the response shape.
    result.set("pagination", detectPagination(parsed, preview));
    // JSON-schema-ish description of the observed response.
    result.set("responseSchema", schemaOf(preview));

    // Per-field evidence: where each inferred value came from and how much
    // confidence the UI should attach (LOW fields require user confirmation).
    String capId = chatCapture.path("id").asText("");
    var evidence = result.putObject("evidence");
    evidence.set("chatPath", ev("HIGH", capId, parsed.path()));
    evidence.set("aiMethod", ev("HIGH", capId, ""));
    evidence.set("requestTemplate", ev(markerReplaced[0] ? "HIGH" : "LOW", capId,
        markerReplaced[0] ? "prompt located in request body" : "prompt not found — placeholder guessed"));
    String convSource = !conversationIds.isEmpty() ? "request body"
        : steps.size() > 1 ? "preceding response" : "none detected";
    evidence.set("conversationId", ev(
        !conversationIds.isEmpty() ? "HIGH" : steps.size() > 1 ? "MEDIUM" : "LOW",
        capId, convSource));
    evidence.set("responseFormat", ev(sse ? "HIGH" : "MEDIUM", capId,
        chatCapture.path("contentType").asText("")));
    String pointer = result.path("aiResponsePointer").asText("");
    evidence.set("responsePointer", ev(
        RESPONSE_POINTERS.contains(pointer) ? "HIGH" : pointer.isBlank() ? "LOW" : "MEDIUM",
        capId, pointer));
    var pag = result.path("pagination");
    evidence.set("pagination", ev(
        "none".equals(pag.path("type").asText("none")) ? "LOW" : "MEDIUM",
        capId, pag.path("pointer").asText("")));
    evidence.set("responseSchema", ev(preview == null ? "LOW" : "MEDIUM", capId, ""));
    return result;
  }

  private static ObjectNode ev(String confidence, String captureId, String detail) {
    var e = JSON.createObjectNode();
    e.put("confidence", confidence);
    if (!captureId.isBlank()) e.putArray("captureIds").add(captureId);
    if (!detail.isBlank()) e.put("detail", detail);
    return e;
  }

  /** Detect cursor / next-link / offset pagination from request + response shape. */
  static ObjectNode detectPagination(ParsedUrl parsed, JsonNode preview) {
    var out = JSON.createObjectNode();
    out.put("type", "none");
    for (String param : parsed.query().split("&")) {
      String key = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
      if (Set.of("offset", "page", "pagenumber", "start").contains(key)) {
        out.put("type", "offset").put("param", key).put("pointer", "query:" + key);
        return out;
      }
      if (Set.of("cursor", "after", "nexttoken", "pagetoken", "continuation").contains(key)) {
        out.put("type", "cursor").put("param", key).put("pointer", "query:" + key);
        return out;
      }
    }
    if (preview != null && preview.isObject()) {
      String[] cursorFields = {"nextcursor", "next_cursor", "nextpagetoken", "cursor",
          "nexttoken", "continuationtoken"};
      String[] linkFields = {"next", "nextlink", "next_link"};
      var found = findField(preview, "", cursorFields, linkFields);
      if (found != null) {
        boolean link = found.toLowerCase(Locale.ROOT).matches(".*(next|nextlink|next_link)$")
            && !found.toLowerCase(Locale.ROOT).contains("cursor")
            && !found.toLowerCase(Locale.ROOT).contains("token");
        out.put("type", link ? "link" : "cursor").put("pointer", found);
      }
    }
    return out;
  }

  private static String findField(JsonNode node, String prefix, String[] cursor, String[] link) {
    var fields = node.fields();
    while (fields.hasNext()) {
      var f = fields.next();
      String name = f.getKey().toLowerCase(Locale.ROOT);
      String ptr = prefix + "/" + f.getKey();
      for (String c : cursor) if (name.equals(c)) return ptr;
      for (String l : link) if (name.equals(l)) return ptr;
      if (f.getValue().isObject() && f.getKey().matches("(?i)(links|paging|pagination|meta)")) {
        String inner = findField(f.getValue(), ptr, cursor, link);
        if (inner != null) return inner;
      }
    }
    return null;
  }

  /** JSON-schema-ish shape description built from a sample response. */
  static ObjectNode schemaOf(JsonNode sample) {
    var schema = JSON.createObjectNode();
    if (sample == null) { schema.put("type", "unknown"); return schema; }
    if (sample.isObject()) {
      schema.put("type", "object");
      var props = schema.putObject("properties");
      sample.fields().forEachRemaining(f -> props.set(f.getKey(), schemaOf(f.getValue())));
    } else if (sample.isArray()) {
      schema.put("type", "array");
      var merged = JSON.createObjectNode();
      for (int i = 0; i < Math.min(3, sample.size()); i++) {
        var item = schemaOf(sample.get(i));
        merged.setAll(item);
      }
      schema.set("items", merged);
    } else if (sample.isNumber()) schema.put("type", "number");
    else if (sample.isBoolean()) schema.put("type", "boolean");
    else schema.put("type", "string");
    return schema;
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
