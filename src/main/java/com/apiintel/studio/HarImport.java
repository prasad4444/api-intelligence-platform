package com.apiintel.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitized HAR import. Keeps only XHR/fetch entries (or, when the resource
 * type is not recorded, requests that are not obviously static assets),
 * strips cookie/authorization/token headers and password-like body fields,
 * and reports everything removed so the user sees what was dropped.
 */
public final class HarImport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonNodeFactory J = JsonNodeFactory.instance;
  private static final long MAX_BYTES = 10L * 1024 * 1024;
  private static final Set<String> DROP_HEADERS = Set.of(
      "cookie", "authorization", "set-cookie", "proxy-authorization", "x-api-key",
      "x-auth-token", "x-access-token", "x-csrf-token", "x-xsrf-token");
  private static final Pattern SECRET_FIELD = Pattern.compile(
      "(?i).*(password|passwd|secret|token|api[_-]?key|credential|session|otp|code|signature).*");
  private static final Pattern STATIC_ASSET = Pattern.compile(
      "(?i)\\.(js|css|png|jpe?g|gif|svg|ico|woff2?|ttf|map|webp)(\\?.*)?$");

  private HarImport() {}

  /**
   * @return {captures:[{id,method,url,headers,body,status,contentType,responsePreview}],
   *          removed:{entries:int,headers:[...],bodyFields:[...]}}
   */
  public static ObjectNode sanitize(byte[] raw) {
    if (raw == null || raw.length == 0) throw new IllegalArgumentException("Empty HAR file");
    if (raw.length > MAX_BYTES) throw new IllegalArgumentException("HAR exceeds the 10MB limit");
    JsonNode har;
    try {
      har = JSON.readTree(raw);
    } catch (Exception e) {
      throw new IllegalArgumentException("Not a valid HAR (JSON) file");
    }
    JsonNode entries = har.path("log").path("entries");
    if (!entries.isArray()) throw new IllegalArgumentException("HAR has no log.entries");

    var out = J.objectNode();
    ArrayNode captures = out.putArray("captures");
    var removed = out.putObject("removed");
    Set<String> removedHeaders = new LinkedHashSet<>();
    Set<String> removedFields = new LinkedHashSet<>();
    int skipped = 0;

    for (JsonNode entry : entries) {
      String resourceType = entry.path("_resourceType").asText("").toLowerCase();
      JsonNode request = entry.path("request");
      String url = request.path("url").asText("");
      if (!url.startsWith("http")) { skipped++; continue; }
      if (!resourceType.isBlank() && !Set.of("xhr", "fetch").contains(resourceType)) {
        skipped++;
        continue;
      }
      String path = url.split("\\?", 2)[0];
      if (resourceType.isBlank() && STATIC_ASSET.matcher(path).find()) { skipped++; continue; }

      var capture = captures.addObject();
      capture.put("id", java.util.UUID.randomUUID().toString());
      capture.put("method", request.path("method").asText("GET"));
      capture.put("url", url);
      var headers = capture.putObject("headers");
      for (JsonNode h : request.path("headers")) {
        String name = h.path("name").asText("").toLowerCase();
        if (name.isBlank()) continue;
        if (DROP_HEADERS.contains(name) || name.startsWith("sec-") || name.startsWith("proxy-")) {
          removedHeaders.add(name);
          continue;
        }
        headers.put(name, h.path("value").asText(""));
      }
      String body = request.path("postData").path("text").asText("");
      if (!body.isBlank()) body = sanitizeBody(body, removedFields);
      capture.put("body", body);
      JsonNode response = entry.path("response");
      capture.put("status", response.path("status").asInt(0));
      String contentType = response.path("content").path("mimeType").asText("");
      capture.put("contentType", contentType);
      String responseText = response.path("content").path("text").asText("");
      capture.put("responsePreview", responseText.length() > 8192
          ? responseText.substring(0, 8192) : responseText);
      if (contentType.contains("event-stream")) capture.put("sse", true);
    }
    removed.put("entries", skipped);
    removedHeaders.forEach(removed.putArray("headers")::add);
    removedFields.forEach(removed.putArray("bodyFields")::add);
    out.put("kept", captures.size());
    return out;
  }

  private static String sanitizeBody(String body, Set<String> removedFields) {
    try {
      JsonNode node = JSON.readTree(body);
      if (!node.isObject()) return body;
      ObjectNode obj = (ObjectNode) node.deepCopy();
      scrub(obj, "", removedFields);
      return obj.toString();
    } catch (Exception e) {
      return body; // non-JSON bodies are kept as-is
    }
  }

  private static void scrub(JsonNode node, String prefix, Set<String> removed) {
    if (node instanceof ObjectNode obj) {
      var it = obj.fields();
      var names = new java.util.ArrayList<String>();
      while (it.hasNext()) {
        var f = it.next();
        String ptr = prefix + "/" + f.getKey();
        if (SECRET_FIELD.matcher(f.getKey()).matches()) {
          removed.add(ptr);
          names.add(f.getKey());
        } else {
          scrub(f.getValue(), ptr, removed);
        }
      }
      names.forEach(obj::remove);
    } else if (node instanceof ArrayNode arr) {
      for (int i = 0; i < arr.size(); i++) scrub(arr.get(i), prefix + "/" + i, removed);
    }
  }
}
