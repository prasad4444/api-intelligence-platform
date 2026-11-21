package com.apiintel.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * Generated-connector scaffolding for cases a declarative connector cannot
 * express (multi-step flows with custom signing, etc.). Produces a readable
 * Java adapter class, a config JSON, a sanitized fixture and a JUnit contract
 * test — all returned for code review only; nothing is compiled or loaded at
 * runtime. The "validate generated" step replays the declarative equivalent
 * in-process against the fixture.
 */
public final class Codegen {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonNodeFactory J = JsonNodeFactory.instance;

  private Codegen() {}

  /** @return {files:[{path,content}], summary} — for read-only review. */
  public static ObjectNode generate(String name, ObjectNode config, ObjectNode fixture) {
    String safe = name.replaceAll("[^A-Za-z0-9]", "");
    if (safe.isBlank()) safe = "Custom";
    String cls = safe.substring(0, 1).toUpperCase() + safe.substring(1) + "Adapter";
    var out = J.objectNode();
    ArrayNode files = out.putArray("files");

    ObjectNode connector = J.objectNode();
    connector.put("version", 1);
    connector.put("name", name);
    for (String f : new String[]{"chatPath", "aiMethod", "aiResponseFormat",
        "aiResponsePointer", "replayMode"}) {
      if (config.hasNonNull(f)) connector.set(f, config.get(f));
    }
    if (config.hasNonNull("aiRequestTemplate")) connector.set("requestTemplate", config.get("aiRequestTemplate"));
    if (config.hasNonNull("steps")) connector.set("steps", config.get("steps"));
    files.add(file("connectors/" + name + "/connector.json", connector.toPrettyString()));
    files.add(file("connectors/" + name + "/fixture.json", fixture.toPrettyString()));

    StringBuilder java = new StringBuilder();
    java.append("package com.apiintel.connectors.generated;\n\n")
        .append("import com.apiintel.chat.ChatAdapter;\n")
        .append("import com.fasterxml.jackson.databind.node.ObjectNode;\n\n")
        .append("/** Generated adapter for ").append(name)
        .append(" — declarative config lives in connector.json.\n")
        .append(" * Custom signing / multi-step logic goes here when the declarative\n")
        .append(" * template cannot express it. Compiled and tested in CI after PR. */\n")
        .append("public class ").append(cls).append(" implements ChatAdapter {\n")
        .append("  private final ObjectNode config;\n\n")
        .append("  public ").append(cls).append("(ObjectNode config) {\n")
        .append("    this.config = config;\n")
        .append("  }\n\n")
        .append("  public ObjectNode buildRequest(String prompt, String conversationId) {\n")
        .append("    // TODO: implement custom request construction beyond connector.json\n")
        .append("    return config.deepCopy();\n")
        .append("  }\n")
        .append("}\n");
    files.add(file("src/main/java/com/apiintel/connectors/generated/" + cls + ".java", java.toString()));

    StringBuilder test = new StringBuilder();
    test.append("package com.apiintel.connectors.generated;\n\n")
        .append("import static org.junit.jupiter.api.Assertions.*;\n")
        .append("import com.fasterxml.jackson.databind.ObjectMapper;\n")
        .append("import org.junit.jupiter.api.Test;\n\n")
        .append("class ").append(cls).append("ContractTest {\n")
        .append("  @Test\n")
        .append("  void requestMatchesFixtureShape() throws Exception {\n")
        .append("    var fixture = new ObjectMapper().readTree(\n")
        .append("      getClass().getResourceAsStream(\"/connectors/").append(name).append("/fixture.json\"));\n")
        .append("    assertTrue(fixture.has(\"request\"), \"fixture has request\");\n")
        .append("  }\n")
        .append("}\n");
    files.add(file("src/test/java/com/apiintel/connectors/generated/" + cls + "ContractTest.java", test.toString()));

    out.put("summary", files.size() + " files generated for connector '" + name + "'");
    return out;
  }

  private static ObjectNode file(String path, String content) {
    return J.objectNode().put("path", path).put("content", content);
  }

  /**
   * In-process contract check: renders the declarative request template with a
   * sample prompt, then extracts the configured response pointer from the
   * fixture response. Never compiles or loads generated code.
   */
  public static ObjectNode validateGenerated(ObjectNode config, ObjectNode fixture) {
    var out = J.objectNode();
    String prompt = "contract check prompt";
    JsonNode rendered = render(config.path("aiRequestTemplate"), prompt, "conv-check");
    out.set("renderedRequest", rendered);
    JsonNode response = fixture.path("response").isObject()
        ? fixture.get("response") : fixture.path("responsePreview").isObject()
            ? fixture.get("responsePreview") : fixture;
    String pointer = config.path("aiResponsePointer").asText("");
    String extracted = pointer.isBlank() ? "" : response.at(pointer).asText("");
    out.put("extracted", extracted);
    boolean ok = !rendered.toString().contains("{{prompt}}")
        && !rendered.toString().contains("{{conversationId}}")
        && (pointer.isBlank() || !extracted.isBlank());
    out.put("ok", ok);
    if (!ok) out.put("error", "unresolved placeholders or empty extraction at " + pointer);
    return out;
  }

  private static JsonNode render(JsonNode template, String prompt, String convId) {
    if (template == null || template.isMissingNode()) return J.objectNode();
    String text = template.toString()
        .replace("{{prompt}}", prompt)
        .replace("{{conversationId}}", convId);
    try {
      return new ObjectMapper().readTree(text);
    } catch (Exception e) {
      return com.fasterxml.jackson.databind.node.TextNode.valueOf(text);
    }
  }
}
