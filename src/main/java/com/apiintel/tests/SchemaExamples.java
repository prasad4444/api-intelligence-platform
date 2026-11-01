package com.apiintel.tests;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded deterministic examples. Generated cases remain reviewable drafts. */
public final class SchemaExamples {

  private SchemaExamples() {}

  public static JsonNode sample(
    JsonNode schema,
    JsonNode document,
    boolean boundary,
    int depth
  ) {
    if (depth > 5) return NullNode.instance;
    if (schema.has("$ref")) {
      String ref = schema.path("$ref").asText();
      return ref.startsWith("#/")
        ? sample(document.at(ref.substring(1)), document, boundary, depth + 1)
        : NullNode.instance;
    }
    if (schema.has("example")) return schema.get("example");
    if (
      schema.path("enum").isArray() && !schema.path("enum").isEmpty()
    ) return schema.path("enum").get(0);
    if (schema.has("default") && !boundary) return schema.get("default");
    String type = schema
      .path("type")
      .asText(schema.has("properties") ? "object" : "string");
    return switch (type) {
      case "object" -> {
        var out = JsonNodeFactory.instance.objectNode();
        schema
          .path("properties")
          .fields()
          .forEachRemaining(e ->
            out.set(
              e.getKey(),
              sample(e.getValue(), document, boundary, depth + 1)
            )
          );
        yield out;
      }
      case "array" -> {
        var out = JsonNodeFactory.instance.arrayNode();
        int count = Math.min(
          10,
          Math.max(1, schema.path(boundary ? "maxItems" : "minItems").asInt(1))
        );
        for (int i = 0; i < count; i++) out.add(
          sample(schema.path("items"), document, boundary, depth + 1)
        );
        yield out;
      }
      case "integer" -> new LongNode(
        schema.path(boundary ? "maximum" : "minimum").asLong(1)
      );
      case "number" -> new DoubleNode(
        schema.path(boundary ? "maximum" : "minimum").asDouble(1)
      );
      case "boolean" -> BooleanNode.TRUE;
      default -> {
        String format = schema.path("format").asText();
        if (format.equals("date")) yield TextNode.valueOf("2026-01-01");
        if (format.equals("date-time")) yield TextNode.valueOf(
          "2026-01-01T00:00:00Z"
        );
        if (format.equals("email")) yield TextNode.valueOf(
          "sample@example.com"
        );
        int length = Math.min(
          1024,
          Math.max(
            1,
            schema.path(boundary ? "maxLength" : "minLength").asInt(6)
          )
        );
        yield TextNode.valueOf("x".repeat(length));
      }
    };
  }

  public static ObjectNode request(
    JsonNode doc,
    String path,
    String method,
    JsonNode operation,
    JsonNode pathItem,
    String type
  ) {
    var req = JsonNodeFactory.instance.objectNode();
    req.put("method", method.toUpperCase());
    boolean boundary = type.equals("boundary");
    var headers = req.putObject("headers");
    headers.put("Content-Type", "application/json");
    String url = "{{baseUrl}}" + path;
    var params = new ArrayList<JsonNode>();
    pathItem.path("parameters").forEach(params::add);
    operation.path("parameters").forEach(params::add);
    for (var param : params) {
      String name = param.path("name").asText(),
        value = sample(param.path("schema"), doc, boundary, 0).asText("sample");
      if (param.path("in").asText().equals("path")) url = url.replace(
        "{" + name + "}",
        URLEncoder.encode(value, StandardCharsets.UTF_8)
      );
      else if (param.path("in").asText().equals("query")) url +=
        (url.contains("?") ? "&" : "?") +
        URLEncoder.encode(name, StandardCharsets.UTF_8) +
        "=" +
        URLEncoder.encode(value, StandardCharsets.UTF_8);
      else if (param.path("in").asText().equals("header")) headers.put(
        name,
        value
      );
    }
    req.put("url", url);
    var schema = operation
      .path("requestBody")
      .path("content")
      .path("application/json")
      .path("schema");
    req.put(
      "body",
      type.equals("negative")
        ? "{invalid"
        : schema.isMissingNode()
          ? ""
          : sample(schema, doc, boundary, 0).toString()
    );
    var security = operation.has("security")
      ? operation.path("security")
      : doc.path("security");
    if (!type.equals("security") && !security.isEmpty()) {
      for (var requirement : security) {
        requirement.fieldNames().forEachRemaining(name -> {
          var scheme = doc
            .path("components")
            .path("securitySchemes")
            .path(name);
          if (
            scheme.path("type").asText().equals("apiKey") &&
            scheme.path("in").asText().equals("header")
          ) headers.put(scheme.path("name").asText(), "{{apiKey}}");
          else headers.put(
            "Authorization",
            scheme.path("scheme").asText().equals("basic")
              ? "Basic {{basicAuth}}"
              : "Bearer {{token}}"
          );
        });
      }
    }
    return req;
  }
}
