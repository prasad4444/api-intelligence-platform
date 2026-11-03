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

  public static JsonNode resolveLocal(JsonNode value,JsonNode document) {
    var refs=new HashSet<String>();JsonNode node=value;
    for(int depth=0;depth<12&&node.has("$ref");depth++){String ref=node.path("$ref").asText();if(!ref.startsWith("#/")||!refs.add(ref))break;var target=document.at(ref.substring(1));if(!target.isObject())break;var merged=((ObjectNode)target).deepCopy();node.fields().forEachRemaining(e->{if(!e.getKey().equals("$ref"))merged.set(e.getKey(),e.getValue());});node=merged;}
    return node;
  }
  public ObjectNode collection(ObjectNode spec) {
    var result=store.parse("{\"info\":{\"name\":\"OpenAPI collection\",\"schema\":\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},\"item\":[]}");
    result.with("info").put("name",spec.path("info").path("title").asText("API"));
    String base=spec.path("servers").path(0).path("url").asText();if(base.isBlank())base="https://api.example.com";
    var server=spec.path("servers").path(0);var fields=server.path("variables").fields();while(fields.hasNext()){var field=fields.next();base=base.replace("{"+field.getKey()+"}",field.getValue().path("default").asText("{"+field.getKey()+"}"));}
    result.putArray("variable").addObject().put("key","baseUrl").put("value",base).put("type","string");
    var items=(ArrayNode)result.get("item");
    spec.path("paths").fields().forEachRemaining(path->{var pathItem=resolveLocal(path.getValue(),spec);pathItem.fields().forEachRemaining(operation->{String method=operation.getKey();if(!Set.of("get","post","put","patch","delete","head","options").contains(method))return;
      var op=(ObjectNode)resolveLocal(operation.getValue(),spec).deepCopy();var parameters=new LinkedHashMap<String,JsonNode>();
      for(var source:List.of(pathItem.path("parameters"),op.path("parameters")))for(var value:source){var parameter=resolveLocal(value,spec);parameters.put(parameter.path("in").asText()+":"+parameter.path("name").asText(),parameter);}
      var normalized=op.putArray("parameters");parameters.values().forEach(normalized::add);var normalizedPath=store.parse("{}");
      if(op.has("requestBody"))op.set("requestBody",resolveLocal(op.get("requestBody"),spec));
      var generated=com.apiintel.tests.SchemaExamples.request(spec,path.getKey(),method,op,normalizedPath,"positive");
      var item=items.addObject();item.put("x-studio-id",UUID.randomUUID().toString());item.put("name",op.path("summary").asText(method.toUpperCase()+" "+path.getKey()));
      var request=item.putObject("request");request.put("method",method.toUpperCase());request.put("url",generated.path("url").asText());request.put("description",op.path("description").asText());var headers=request.putArray("header");
      generated.path("headers").fields().forEachRemaining(h->headers.addObject().put("key",h.getKey()).put("value",h.getValue().asText()));
      var content=op.path("requestBody").path("content");String media=content.has("application/json")?"application/json":content.fieldNames().hasNext()?content.fieldNames().next():"";
      if(!media.isBlank()) {var definition=content.path(media);String body="";if(definition.has("example"))body=definition.path("example").isTextual()?definition.path("example").asText():definition.path("example").toString();else if(media.contains("json"))body=com.apiintel.tests.SchemaExamples.sample(definition.path("schema"),spec,false,0).toString();
        var raw=request.putObject("body");raw.put("mode","raw");raw.put("raw",body);raw.putObject("options").putObject("raw").put("language",media.contains("json")?"json":media.contains("xml")?"xml":"text");for(var h:headers)if(h.path("key").asText().equalsIgnoreCase("Content-Type"))((ObjectNode)h).put("value",media);
      }
      var successCodes=new ArrayList<String>();op.path("responses").fieldNames().forEachRemaining(code->{if(code.matches("2[0-9][0-9]"))successCodes.add(code);});var checks=item.putArray("x-studio-checks");if(successCodes.size()==1)checks.addObject().put("name","Status is "+successCodes.get(0)).put("type","status").put("key","").put("expected",successCodes.get(0)).put("enabled",true);
      var responses=item.putArray("response");op.path("responses").fields().forEachRemaining(r->{var response=resolveLocal(r.getValue(),spec);if(!r.getKey().matches("[1-5][0-9][0-9]"))return;var saved=responses.addObject();saved.put("name",r.getKey()+" "+response.path("description").asText());saved.put("code",Integer.parseInt(r.getKey()));saved.put("status",response.path("description").asText());saved.set("originalRequest",request.deepCopy());saved.putArray("header");var responseContent=response.path("content");var json=responseContent.path("application/json");saved.put("body",json.has("example")?json.get("example").toString():json.has("schema")?com.apiintel.tests.SchemaExamples.sample(json.path("schema"),spec,false,0).toString():"");});
    });});return result;
  }
}
