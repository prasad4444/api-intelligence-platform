package com.apiintel.workspace;

import com.apiintel.network.RequestService;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OperationExecution {
  private static final Set<String> METHODS = Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE");
  private final RecordStore store;
  private final RequestService requests;

  public OperationExecution(RecordStore store, RequestService requests) {
    this.store = store;
    this.requests = requests;
  }

  public ObjectNode execute(String id, ObjectNode input, String actor) {
    var spec = store.get("specs", id);
    if (!input.path("expectedRevision").isIntegralNumber()
        || !input.path("expectedRevision").equals(spec.path("revision"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The active contract changed. Reload it before executing.");
    }
    var document = spec.path("document");
    String key = input.path("operation").asText();
    int separator = key.indexOf(' ');
    if (separator < 0) throw invalid("Select an operation from the saved contract");
    String method = key.substring(0, separator);
    String path = key.substring(separator + 1);
    if (!METHODS.contains(method) || !path.startsWith("/") || path.startsWith("//")
        || path.contains("?") || path.contains("#") || path.contains("\\")) {
      throw invalid("This operation cannot be executed");
    }
    var pathItem = SpecService.resolveLocal(document.path("paths").path(path), document);
    var operation = SpecService.resolveLocal(pathItem.path(method.toLowerCase(Locale.ROOT)), document);
    if (!operation.isObject() || operation.has("$ref")) throw invalid("Operation is missing or has an unresolved reference");

    var servers = operation.has("servers") ? operation.path("servers")
      : pathItem.has("servers") ? pathItem.path("servers") : document.path("servers");
    int serverIndex = input.path("serverIndex").asInt(0);
    var server = servers.path(serverIndex);
    String base = server.path("url").asText();
    for (var variable : iterable(server.path("variables").fields())) {
      if (!variable.getValue().has("default")) throw invalid("The server variable " + variable.getKey() + " needs a default");
      base = base.replace("{" + variable.getKey() + "}", variable.getValue().path("default").asText());
    }
    URI baseUri;
    try { baseUri = URI.create(base); }
    catch (IllegalArgumentException e) { throw invalid("The selected server is not a valid absolute HTTP(S) URL"); }
    if (!Set.of("http", "https").contains(Objects.toString(baseUri.getScheme(), ""))
        || baseUri.getHost() == null || baseUri.getUserInfo() != null
        || baseUri.getQuery() != null || baseUri.getFragment() != null) {
      throw invalid("The selected server must be an absolute HTTP(S) URL without credentials or a query");
    }

    var request = store.parse("{}").put("method", method);
    var headers = request.putObject("headers");
    var query = new ArrayList<String>();
    var parameters = new LinkedHashMap<String, JsonNode>();
    for (var source : List.of(pathItem.path("parameters"), operation.path("parameters"))) {
      for (var raw : source) {
        var parameter = SpecService.resolveLocal(raw, document);
        if (parameter.has("$ref") || !parameter.hasNonNull("name")) throw invalid("Resolve all parameter references before executing");
        parameters.put(parameter.path("in").asText() + ":" + parameter.path("name").asText(), parameter);
      }
    }
    for (var entry : parameters.entrySet()) {
      var parameter = entry.getValue();
      String location = parameter.path("in").asText();
      String name = parameter.path("name").asText();
      var value = input.path("parameters").path(entry.getKey());
      if (value.isMissingNode() || value.isNull() || value.isTextual() && value.asText().isBlank()) {
        if (location.equals("path") || parameter.path("required").asBoolean()) throw invalid("Enter required " + location + " parameter " + name);
        continue;
      }
      String style = parameter.path("style").asText(location.equals("query") ? "form" : "simple");
      switch (location) {
        case "path" -> {
          if (!style.equals("simple") || value.isContainerNode()) throw invalid("Unsupported path serialization for " + name);
          path = path.replace("{" + name + "}", encode(value.asText()));
        }
        case "query" -> addQuery(query, name, style, parameter.path("explode").asBoolean(style.equals("form")), value);
        case "header" -> {
          if (!style.equals("simple") || value.isContainerNode()) throw invalid("Unsupported header serialization for " + name);
          if (name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("cookie")) {
            throw invalid("Use a saved security profile for " + name);
          }
          headers.put(name, value.asText());
        }
        default -> throw invalid("Unsupported parameter location: " + location);
      }
    }
    if (path.contains("{") || path.contains("}")) throw invalid("The operation has unresolved path parameters");

    var body = SpecService.resolveLocal(operation.path("requestBody"), document);
    String media = input.path("mediaType").asText();
    String payload = input.path("body").asText();
    if (body.has("$ref")) throw invalid("Resolve the request body reference before executing");
    if (payload.isBlank() && body.path("required").asBoolean()) throw invalid("Enter a request body");
    if (!payload.isBlank()) {
      if (!body.path("content").has(media) || !media.matches("(?i)(application/(?:[a-z0-9.+-]+[+])?(?:json|xml)|application/x-www-form-urlencoded|text/[a-z0-9.+-]+)")) {
        throw invalid("This request body media type is not supported for execution");
      }
      if (media.toLowerCase(Locale.ROOT).endsWith("json")) {
        try { new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload); }
        catch (Exception e) { throw invalid("The request body is not valid JSON"); }
      }
      request.put("body", payload);
      headers.put("Content-Type", media);
    }

    addSecurity(document, operation, input, request, headers, query);
    request.put("url", base.replaceFirst("/$", "") + path + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    return requests.send(request, actor);
  }

  private void addSecurity(JsonNode document, JsonNode operation, ObjectNode input, ObjectNode request, ObjectNode headers, List<String> query) {
    var security = operation.has("security") ? operation.path("security") : document.path("security");
    if (security.isEmpty()) return;
    var alternative = security.path(input.path("securityIndex").asInt(0));
    if (!alternative.isObject()) throw invalid("Select a documented security alternative");
    if (alternative.isEmpty()) return;
    String profile = input.path("environmentId").asText();
    if (profile.isBlank()) throw invalid("Select a saved environment for this security alternative");
    var environment = store.get("environments", profile);
    request.put("environmentId", profile);
    for (String name : iterable(alternative.fieldNames())) {
      var scheme = SpecService.resolveLocal(document.path("components").path("securitySchemes").path(name), document);
      var selected = input.path("credentials").path(name);
      String secretKey = selected.path("secret").asText();
      boolean savedSecret = false;
      for (var value : environment.path("values")) {
        if (value.path("enabled").asBoolean(true) && value.path("secret").asBoolean()
            && value.path("key").asText().equals(secretKey)) savedSecret = true;
      }
      if (!savedSecret) throw invalid("Select an enabled secret variable from the environment");
      String secret = variable(secretKey);
      if (scheme.path("type").asText().equals("apiKey")) {
        String field = scheme.path("name").asText();
        switch (scheme.path("in").asText()) {
          case "header" -> headers.put(field, secret);
          case "query" -> query.add(encode(field) + "=" + secret);
          default -> throw invalid("Cookie API keys are unavailable in this executor");
        }
      } else if (scheme.path("type").asText().equals("http") && scheme.path("scheme").asText().equalsIgnoreCase("basic")) {
        if (request.has("auth") || headers.has("Authorization")) throw invalid("Conflicting Authorization schemes");
        request.putObject("auth").put("type", "basic").put("username", variable(selected.path("username").asText())).put("password", secret);
      } else if (scheme.path("type").asText().equals("http") && scheme.path("scheme").asText().equalsIgnoreCase("bearer")
          || Set.of("oauth2", "openIdConnect").contains(scheme.path("type").asText())) {
        if (request.has("auth") || headers.has("Authorization")) throw invalid("Conflicting Authorization schemes");
        headers.put("Authorization", "Bearer " + secret);
      } else throw invalid("Unsupported security scheme: " + name);
    }
  }

  private void addQuery(List<String> query, String name, String style, boolean explode, JsonNode value) {
    if (style.equals("deepObject") && value.isObject()) {
      for (var pair : iterable(value.fields())) {
        if (!pair.getValue().isValueNode()) throw invalid("Nested deepObject values are unsupported");
        query.add(encode(name + "[" + pair.getKey() + "]") + "=" + encode(pair.getValue().asText()));
      }
    } else if (value.isArray() && Set.of("form", "spaceDelimited", "pipeDelimited").contains(style)) {
      var values = new ArrayList<String>();
      for (var item : value) {
        if (!item.isValueNode()) throw invalid("Nested query arrays are unsupported");
        values.add(encode(item.asText()));
      }
      if (style.equals("form") && explode) values.forEach(item -> query.add(encode(name) + "=" + item));
      else query.add(encode(name) + "=" + String.join(style.equals("spaceDelimited") ? "%20" : style.equals("pipeDelimited") ? "%7C" : ",", values));
    } else if (style.equals("form") && value.isValueNode()) {
      query.add(encode(name) + "=" + encode(value.asText()));
    } else throw invalid("Unsupported query serialization for " + name);
  }

  private static <T> Iterable<T> iterable(Iterator<T> iterator) { return () -> iterator; }
  private static String variable(String key) {
    if (!key.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,99}")) throw invalid("Choose a configured credential variable");
    return "{{" + key + "}}";
  }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
  private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
