package com.apiintel.tests;

import com.apiintel.network.RequestService;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TestService {

  private final RecordStore store;
  private final RequestService requests;

  public TestService(RecordStore s, RequestService r) {
    store = s;
    requests = r;
  }

  public List<ObjectNode> generate(String specId, String actor) {
    var spec = store.get("specs", specId);
    var generated = new ArrayList<ObjectNode>();
    String contractHash;
    try { contractHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(spec.path("document").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    spec
      .path("document")
      .path("paths")
      .fields()
      .forEachRemaining(p ->
        com.apiintel.workspace.SpecService.resolveLocal(p.getValue(),spec.path("document"))
          .fields()
          .forEachRemaining(o -> {
            if (
              !Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(
                o.getKey()
              )
            ) return;
            for (var type : List.of(
              "positive",
              "negative",
              "boundary",
              "security"
            )) {
              String operation = o.getKey().toUpperCase() + " " + p.getKey();
              String fingerprint = specId + ":" + contractHash + ":" + operation + ":" + type;
              var existing = store
                .list("tests")
                .stream()
                .filter(t -> t.path("fingerprint").asText().equals(fingerprint))
                .findFirst();
              if (existing.isPresent()) {
                generated.add(existing.get());
                continue;
              }
              var t = store.parse("{}");
              t.put("fingerprint", fingerprint);
              t.put("title", type + " · " + operation);
              t.put("specId", specId);
              t.put("specRevision", spec.path("revision").asInt());
              t.put("contractHash", contractHash);
              t.put("operation", operation);
              t.put("type", type);
              t.put("priority", type.equals("security") ? "High" : "Medium");
              t.put("status", "Draft");
              t.put("author", actor);
              t.put(
                "preconditions",
                "Configure base URL, credentials and operation-specific examples before execution"
              );
              t.putArray("tags").add(type);
              t.putArray("steps")
                .add("Prepare " + type + " request")
                .add("Send request")
                .add("Verify exact expected status and response assertions");
              t.set(
                "request",
                SchemaExamples.request(
                  spec.path("document"),
                  p.getKey(),
                  o.getKey(),
                  com.apiintel.workspace.SpecService.resolveLocal(o.getValue(),spec.path("document")),
                  com.apiintel.workspace.SpecService.resolveLocal(p.getValue(),spec.path("document")),
                  type
                )
              );
              var security = com.apiintel.workspace.SpecService.resolveLocal(o.getValue(),spec.path("document")).has("security")
                ? com.apiintel.workspace.SpecService.resolveLocal(o.getValue(),spec.path("document")).path("security")
                : spec.path("document").path("security");
              String expected = type.equals("negative")
                ? "400"
                : type.equals("security") && !security.isEmpty()
                  ? "401"
                  : "200";
              if (
                type.equals("positive") ||
                type.equals("boundary") ||
                (type.equals("security") && security.isEmpty())
              ) {
                var codes = com.apiintel.workspace.SpecService.resolveLocal(o.getValue(),spec.path("document")).path("responses").fieldNames();
                while (codes.hasNext()) {
                  String code = codes.next();
                  if (code.matches("2[0-9][0-9]")) {
                    expected = code;
                    break;
                  }
                }
              }
              t.put("expectedStatus", Integer.parseInt(expected));
              t.put("expectedBodyContains", "");
              generated.add(store.save("tests", t, actor));
            }
          })
      );
    return generated;
  }

  public ObjectNode run(String id, Map<String, String> env, String actor) {
    var t = store.get("tests", id);
    var req = (ObjectNode) t.path("request").deepCopy();
    for (String k : List.of("url", "body")) {
      String value = req.path(k).asText();
      for (var v : env.entrySet())
        value = value.replace("{{" + v.getKey() + "}}", v.getValue());
      req.put(k, value);
    }
    if (
      t.path("expectedStatus").asInt() < 100 ||
      t.path("expectedStatus").asInt() > 599
    ) throw new IllegalArgumentException(
      "Set an expected HTTP status between 100 and 599 before running"
    );
    var headers = req.path("headers");
    if (headers.isObject()) headers.fields().forEachRemaining(h -> {
      String value = h.getValue().asText();
      for (var v : env.entrySet()) value = value.replace("{{" + v.getKey() + "}}", v.getValue());
      ((ObjectNode) headers).put(h.getKey(), value);
    });
    for (String key : List.of("environmentId", "globalProfileId", "collectionProfileId", "collectionId")) if (env.containsKey(key)) req.put(key,env.get(key));
    if (!req.has("environmentId") && !req.has("globalProfileId") && req.toString().matches("(?s).*\\{\\{[^}]+\\}\\}.*")) throw new IllegalArgumentException("Resolve all request variables before execution");
    var response = requests.send(req, actor);
    var run = store.parse("{}");
    run.put("caseId", id);
    run.put("specId", t.path("specId").asText());
    run.put("specRevision", t.path("specRevision").asInt());
    run.put("caseRevision", t.path("revision").asInt());
    run.put(
      "status",
      response.path("status").asInt() == t.path("expectedStatus").asInt() &&
        response
          .path("body")
          .asText()
          .contains(t.path("expectedBodyContains").asText())
        ? "PASS"
        : "FAIL"
    );
    run.set("response", response);
    return store.save("runs", run, actor);
  }

  public Object runSuite(
    String kind,
    String id,
    Map<String, String> env,
    String actor
  ) {
    if (
      !Set.of("suites", "plans").contains(kind)
    ) throw new IllegalArgumentException("Choose suites or plans");
    var suite = store.get(kind, id);
    var results = new ArrayList<ObjectNode>();
    for (var caseId : suite.path("caseIds"))
      results.add(run(caseId.asText(), env, actor));
    return results;
  }

  public String export(String format) {
    var tests = store.list("tests");
    if (format.equals("json")) return tests.toString();
    if (format.equals("postman")) {
      var c = store.parse(
        "{\"info\":{\"name\":\"Test library\",\"schema\":\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},\"item\":[]}"
      );
      for (var t : tests) {
        var i = ((ArrayNode) c.get("item")).addObject();
        i.put("name", t.path("title").asText());
        var source = t.path("request");
        var request = i.putObject("request");
        request.put("method", source.path("method").asText());
        request.put("url", source.path("url").asText());
        request
          .putObject("body")
          .put("mode", "raw")
          .put("raw", source.path("body").asText());
        var headers = request.putArray("header");
        source
          .path("headers")
          .fields()
          .forEachRemaining(h ->
            headers
              .addObject()
              .put("key", h.getKey())
              .put("value", h.getValue().asText())
          );
        var event = i.putArray("event").addObject();
        event.put("listen", "test");
        event
          .putObject("script")
          .putArray("exec")
          .add(
            "pm.test('Expected status', () => pm.response.to.have.status(" +
              t.path("expectedStatus").asInt() +
              "));"
          );
      }
      return c.toPrettyString();
    }
    if (format.equals("csv")) {
      var b = new StringBuilder(
        "Title,Type,Priority,Operation,Expected Status\n"
      );
      for (var t : tests)
        b.append(csv(t.path("title").asText()))
          .append(',')
          .append(csv(t.path("type").asText()))
          .append(',')
          .append(csv(t.path("priority").asText()))
          .append(',')
          .append(csv(t.path("operation").asText()))
          .append(',')
          .append(t.path("expectedStatus").asInt())
          .append('\n');
      return b.toString();
    }
    if (format.equals("cucumber")) {
      var b = new StringBuilder("Feature: API contract verification\n");
      for (var t : tests)
        b.append("  Scenario: ")
          .append(t.path("title").asText().replaceAll("[\r\n]", " "))
          .append("\n    Given a configured API base URL\n    When I send ")
          .append(t.path("operation").asText())
          .append("\n    Then the response status is ")
          .append(t.path("expectedStatus").asInt())
          .append("\n\n");
      return b.toString();
    }
    if (format.equals("junit")) {
      var b = new StringBuilder(
        "import org.junit.jupiter.api.Test;\nimport static io.restassured.RestAssured.*;\nclass GeneratedApiTests {\n"
      );
      int i = 0;
      for (var t : tests) {
        var req = t.path("request");
        String path = req.path("url").asText().replace("{{baseUrl}}", "");
        b.append(" @Test void case")
          .append(++i)
          .append(
            "() { given().baseUri(System.getProperty(\"baseUrl\")).when().request(\""
          )
          .append(req.path("method").asText())
          .append("\", ")
          .append(TextNode.valueOf(path))
          .append(").then().statusCode(")
          .append(t.path("expectedStatus").asInt())
          .append("); }\n");
      }
      return b.append("}\n").toString();
    }
    throw new IllegalArgumentException("Unsupported export format");
  }

  private String csv(String s) {
    if (s.matches("^[=+@-].*")) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }
}
