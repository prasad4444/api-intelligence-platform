package com.apiintel.chat;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for chat adapters: capability objects, SSE parsing, error mapping. */
public final class ChatSupport {
  public static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d{3})");

  private ChatSupport() {}

  public static ObjectNode capabilities(String mode, boolean streaming, boolean multiTurn,
      boolean contextAttach, boolean browserBacked) {
    var caps = JSON.createObjectNode();
    caps.put("mode", mode);
    caps.put("streaming", streaming);
    caps.put("multiTurn", multiTurn);
    caps.put("contextAttach", contextAttach);
    caps.put("browserBacked", browserBacked);
    return caps;
  }

  public static ChatEvent delta(String text) {
    return ChatEvent.of("delta", JSON.createObjectNode().put("text", text));
  }

  public static ChatEvent task(String sessionId, String status, String url) {
    var data = JSON.createObjectNode().put("sessionId", sessionId).put("status", status).put("url", url);
    return ChatEvent.of("task", data);
  }

  /** Map an upstream failure to a structured error event with a recommended UI action. */
  public static ChatEvent error(Throwable error) {
    String message = error.getMessage() == null ? "Provider request failed" : error.getMessage();
    var data = JSON.createObjectNode().put("message", message);
    int status = statusOf(message);
    data.put("upstreamStatus", status);
    if (status == 401 || status == 403
        || message.toLowerCase(Locale.ROOT).contains("reconnect")
        || message.toLowerCase(Locale.ROOT).contains("expired")) {
      data.put("code", "RECONNECT_REQUIRED").put("action", "RECONNECT");
    } else if (status == 400 || status == 404 || status == 405 || status == 422) {
      data.put("code", "BAD_CONFIG").put("action", "FIX_CONFIG");
    } else {
      data.put("code", status == 0 ? "PROVIDER_ERROR" : "UPSTREAM_" + status).put("action", "RETRY");
    }
    return ChatEvent.of("error", data);
  }

  public static int statusOf(String message) {
    Matcher m = HTTP_STATUS.matcher(message);
    return m.find() ? Integer.parseInt(m.group(1)) : 0;
  }

  /**
   * Parse one SSE {@code data:} payload and extract the delta text at
   * {@code pointer} (empty pointer = whole JSON serialized / raw text).
   */
  public static String sseDelta(String payload, JsonPointer pointer) {
    if (payload == null || payload.isBlank() || payload.equals("[DONE]")) return "";
    try {
      JsonNode value = JSON.readTree(payload);
      JsonNode selected = pointer == null || pointer.matches() ? value : value.at(pointer);
      return selected.isTextual() ? selected.asText() : "";
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Split a raw SSE byte stream into {@code data:} payloads. {@code source} is
   * the concatenation of response lines as emitted by WebClient/reactor
   * {@code bodyToFlux(String.class)} (line chunks) — the iterable may also be
   * full "data: x" lines.
   */
  public static java.util.List<String> ssePayloads(Iterable<String> chunks) {
    var out = new java.util.ArrayList<String>();
    var current = new StringBuilder();
    for (String chunk : chunks) {
      for (String line : chunk.split("\\r?\\n", -1)) {
        if (line.isEmpty()) {
          if (current.length() > 0) {
            out.add(current.toString());
            current.setLength(0);
          }
          continue;
        }
        if (line.startsWith("data:")) {
          if (current.length() > 0) current.append('\n');
          current.append(line.substring(5).replaceFirst("^ ", ""));
          continue;
        }
        // Some decoders strip the data: prefix and deliver bare JSON payloads.
        if (current.length() > 0) {
          out.add(current.toString());
          current.setLength(0);
        }
        if (line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) continue;
        out.add(line);
      }
    }
    if (current.length() > 0) out.add(current.toString());
    return out;
  }

  public static JsonPointer pointer(String expression) {
    try {
      return JsonPointer.compile(expression == null ? "" : expression.trim());
    } catch (IllegalArgumentException e) {
      return JsonPointer.compile("");
    }
  }
}
