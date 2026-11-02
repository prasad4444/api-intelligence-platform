package com.apiintel.workspace;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SpecService {

  private final RecordStore store;

  public SpecService(RecordStore s) {
    store = s;
  }

  public ObjectNode parse(String source) {
    try {
      JsonNode n = new ObjectMapper(new YAMLFactory()).readTree(source);
      if (
        !(n instanceof ObjectNode) ||
        !n.path("openapi").asText().startsWith("3.") ||
        !n.path("paths").isObject()
      ) throw new IllegalArgumentException(
        "An OpenAPI 3 document with paths is required"
      );
      return (ObjectNode) n;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(
        "Cannot parse OpenAPI: " + e.getMessage()
      );
    }
  }

  public List<Map<String, String>> lint(ObjectNode spec) {
    var findings = new ArrayList<Map<String, String>>();
    var ids = new HashSet<String>();
    if (!spec.path("info").has("version")) findings.add(
      Map.of(
        "severity",
        "ERROR",
        "path",
        "info.version",
        "message",
        "Version is required"
      )
    );
    spec
      .path("paths")
      .fields()
      .forEachRemaining(path ->
        path
          .getValue()
          .fields()
          .forEachRemaining(op -> {
            if (
              !Set.of(
                "get",
                "post",
                "put",
                "patch",
                "delete",
                "options",
                "head",
                "trace"
              ).contains(op.getKey())
            ) return;
            String where = op.getKey().toUpperCase() + " " + path.getKey();
            String id = op.getValue().path("operationId").asText();
            if (id.isBlank() || !ids.add(id)) findings.add(
              Map.of(
                "severity",
                "ERROR",
                "path",
                where,
                "message",
                "Missing or duplicate operationId"
              )
            );
            if (!op.getValue().has("summary")) findings.add(
              Map.of(
                "severity",
                "WARNING",
                "path",
                where,
                "message",
                "Add an operation summary"
              )
            );
            if (!op.getValue().has("responses")) findings.add(
              Map.of(
                "severity",
                "ERROR",
                "path",
                where,
                "message",
                "Responses are required"
              )
            );
            if (
              !spec.has("security") && !op.getValue().has("security")
            ) findings.add(
              Map.of(
                "severity",
                "WARNING",
                "path",
                where,
                "message",
                "Declare security requirements explicitly"
              )
            );
          })
      );
    var configured = store.list("rules");
    findings.removeIf(f -> {
      String check = f.get("message").contains("operationId")
        ? "operationId"
        : f.get("message").contains("summary")
          ? "summary"
          : f.get("message").contains("Responses")
            ? "responses"
            : f.get("message").contains("Version")
              ? "version"
              : "security";
      return configured
        .stream()
        .anyMatch(
          r ->
            r.path("check").asText().equals(check) &&
            !r.path("enabled").asBoolean(true)
        );
    });
    var adjusted = new ArrayList<Map<String, String>>();
    for (var f : findings) {
      String check = f.get("message").contains("operationId")
        ? "operationId"
        : f.get("message").contains("summary")
          ? "summary"
          : f.get("message").contains("Responses")
            ? "responses"
            : f.get("message").contains("Version")
              ? "version"
              : "security";
      var copy = new HashMap<>(f);
      configured
        .stream()
        .filter(r -> r.path("check").asText().equals(check))
        .findFirst()
        .ifPresent(r ->
          copy.put("severity", r.path("severity").asText("WARNING"))
        );
      adjusted.add(copy);
    }
    return adjusted;
  }

  public List<Map<String, String>> diff(JsonNode a, JsonNode b) {
    var out = new ArrayList<Map<String, String>>();
    compare("", a, b, out);
    return out;
  }

  private void compare(
    String path,
    JsonNode a,
    JsonNode b,
    List<Map<String, String>> out
  ) {
    if (a.equals(b)) return;
    if (a.isObject() && b.isObject()) {
      var keys = new TreeSet<String>();
      a.fieldNames().forEachRemaining(keys::add);
      b.fieldNames().forEachRemaining(keys::add);
      for (var k : keys) compare(path + "/" + k, a.path(k), b.path(k), out);
    } else out.add(
      Map.of(
        "path",
        path,
        "before",
        a.isMissingNode() ? "<missing>" : a.toString(),
        "after",
        b.isMissingNode() ? "<missing>" : b.toString(),
        "risk",
        b.isMissingNode() ? "BREAKING" : "REVIEW"
      )
    );
  }

  public ObjectNode collection(ObjectNode spec) {
    var result = store.parse(
      "{\"info\":{\"name\":\"OpenAPI collection\",\"schema\":\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},\"item\":[]}"
    );
    result
      .with("info")
      .put("name", spec.path("info").path("title").asText("API"));
    var items = (ArrayNode) result.get("item");
    spec
      .path("paths")
      .fields()
      .forEachRemaining(p ->
        p
          .getValue()
          .fields()
          .forEachRemaining(o -> {
            if (
              !Set.of(
                "get",
                "post",
                "put",
                "patch",
                "delete",
                "head",
                "options"
              ).contains(o.getKey())
            ) return;
            var item = items.addObject();
            item.put("name", o.getValue().path("summary").asText(p.getKey()));
            var req = item.putObject("request");
            req.put("method", o.getKey().toUpperCase());
            req.put("url", "{{baseUrl}}" + p.getKey());
            req.putArray("header");
          })
      );
    return result;
  }
}
