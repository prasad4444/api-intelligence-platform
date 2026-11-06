package com.apiintel.integrations;

import com.apiintel.security.Vault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

final class AiEndpointSettings {
  private static final Pattern VAULT_REF = Pattern.compile("\\{\\{vault:([a-zA-Z0-9_-]{1,60})}}");
  private static final Set<String> PUBLIC_HEADERS = Set.of("accept", "content-type", "x-api-version", "anthropic-version", "x-client-version");
  private static final Set<String> BLOCKED_HEADERS = Set.of("authorization", "cookie", "host", "connection", "content-length",
    "transfer-encoding", "proxy-authorization", "upgrade", "expect", "te", "trailer", "forwarded");

  private AiEndpointSettings() {}

  static String method(ObjectNode config) {
    String method = config.path("aiMethod").asText("POST").toUpperCase(Locale.ROOT);
    if (!Set.of("GET", "POST").contains(method)) throw new IllegalArgumentException("Choose GET or POST for AI Tools. Use the approval queue for other methods.");
    return method;
  }

  static void validate(ObjectNode config) {
    method(config);
    var headers = config.path("aiHeaders");
    if (headers.isMissingNode()) return;
    if (!headers.isObject() || headers.size() > 20) throw new IllegalArgumentException("Configure up to 20 AI headers as a JSON object");
    var seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    headers.fields().forEachRemaining(header -> {
      String key = header.getKey().toLowerCase(Locale.ROOT);
      if (!header.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,79}") || BLOCKED_HEADERS.contains(key)
          || key.startsWith("x-forwarded-") || !seen.add(key)) {
        throw new IllegalArgumentException("One or more custom header names are reserved or duplicated");
      }
      if (!header.getValue().isTextual() || header.getValue().asText().length() > 256
          || header.getValue().asText().matches("(?s).*[\\r\\n].*")) {
        throw new IllegalArgumentException("Header values must be single-line strings");
      }
      if (!VAULT_REF.matcher(header.getValue().asText()).matches() && !PUBLIC_HEADERS.contains(key)) {
        throw new IllegalArgumentException("Use {{vault:reference-name}} for custom or sensitive headers");
      }
    });
  }

  static void apply(ObjectNode config, HttpHeaders headers, Vault vault) {
    validate(config);
    config.path("aiHeaders").fields().forEachRemaining(header -> {
      if (headers.containsKey(header.getKey())) throw new IllegalArgumentException("A custom header conflicts with the selected authentication method");
      JsonNode value = header.getValue();
      var ref = VAULT_REF.matcher(value.asText());
      String resolved = value.asText();
      if (ref.matches()) {
        resolved = vault.get(ref.group(1));
        if (resolved.isBlank()) throw new IllegalArgumentException("A custom header's vault reference is empty or missing");
      }
      if (resolved.matches("(?s).*[\\r\\n].*")) throw new IllegalArgumentException("Invalid header credential format");
      headers.set(header.getKey(), resolved);
    });
  }
}
