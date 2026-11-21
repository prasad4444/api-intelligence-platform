package com.apiintel.integrations;

import com.apiintel.diagnostics.ApiRequestLog;
import com.apiintel.store.RecordStore;
import com.apiintel.workspace.SpecService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ai-tools")
@PreAuthorize("isAuthenticated()")
public class AiToolsController {
  private static final Set<String> BUILTIN = Set.of("openai", "azure", "anthropic", "ollama");
  private final RecordStore store;
  private final IntegrationService integrations;
  private final ConnectionService connections;
  private final com.apiintel.chat.ChatContextService contexts;
  private final com.apiintel.chat.ConfirmationGrants grants;

  public AiToolsController(
    RecordStore store,
    IntegrationService integrations,
    ConnectionService connections,
    ReplayService replay,
    com.apiintel.chat.ChatContextService contexts,
    com.apiintel.chat.ConfirmationGrants grants
  ) {
    this.store = store;
    this.integrations = integrations;
    this.connections = connections;
    this.contexts = contexts;
    this.grants = grants;
  }

  private record Prepared(ObjectNode connector, ObjectNode context, String prompt, String fingerprint) {}

  @GetMapping("/{id}/status")
  public ObjectNode status(@PathVariable String id) {
    integrations.authorize(id);
    var config = integrations.config(id);
    var check = priorCheck(id);
    if (check != null && check.path("connectionRevision").equals(config.path("revision"))) return check;
    return store.parse("{}").put("state", config.path("enabled").asBoolean() ? "UNTESTED" : "DISABLED");
  }

  @PostMapping("/{id}/test")
  public ObjectNode test(@PathVariable String id, @RequestBody ObjectNode input, Principal principal) {
    var connector = connection(id, input);
    if (connector.path("aiProbePath").asText().isBlank()
        && !AiEndpointSettings.method(connector).equals("GET") && !input.path("confirmSyntheticPrompt").asBoolean()) {
      throw new IllegalArgumentException("Confirm the synthetic test prompt or configure a read-only probe");
    }
    String state = "CONNECTED";
    String message = "The configured provider responded successfully.";
    try {
      integrations.testAi(connector, principal.getName());
    } catch (IllegalArgumentException e) {
      state = "ERROR";
      message = e.getMessage();
    }
    var check = priorCheck(id);
    if (check == null) check = store.parse("{}").put("id", checkId(id));
    check.put("connectionRevision", connector.path("revision").asInt()).put("state", state)
      .put("message", message).put("testedAt", Instant.now().toString()).put("requestId", ApiRequestLog.requestId());
    return store.save("ai-checks", check, principal.getName());
  }

  @PostMapping("/{id}/preview")
  public synchronized ObjectNode preview(@PathVariable String id, @RequestBody ObjectNode input, Principal principal) {
    var prepared = prepare(id, input, principal.getName());
    var result = store.parse("{}").put("confirmationId", grants.issue(principal.getName(), prepared.fingerprint()))
      .put("destination", URI.create(prepared.connector().path("baseUrl").asText()).getHost())
      .put("prompt", prepared.prompt());
    result.set("context", prepared.context());
    return result;
  }

  @PostMapping("/{id}/send")
  public ObjectNode send(@PathVariable String id, @RequestBody ObjectNode input, Principal principal) {
    var prepared = prepare(id, input, principal.getName());
    grants.consume(input.path("confirmationId").asText(), principal.getName(), prepared.fingerprint());
    var result = store.parse("{}").put("requestId", ApiRequestLog.requestId());
    if (prepared.connector().path("aiRequiresApproval").asBoolean()) {
      var proposal = integrations.proposeAi(prepared.connector(), prepared.prompt(), principal.getName());
      result.put("proposalId", proposal.path("id").asText()).put("status", "PENDING");
    } else result.set("result", integrations.chat(prepared.connector(), prepared.prompt(), principal.getName()));
    return result;
  }

  private ObjectNode connection(String id, ObjectNode input) {
    integrations.authorize(id);
    var connector = integrations.config(id);
    if (connector.path("ownerId").isTextual() && !connector.path("ownerId").asText().isBlank()) {
      if (!"CONNECTED".equals(connector.path("state").asText()) && !connector.path("enabled").asBoolean()) {
        throw new IllegalArgumentException("Connect your own account before using AI Tools with this provider");
      }
      connector = connections.runtimeConfig(connector);
    }
    if (!connector.path("enabled").asBoolean()) throw new IllegalArgumentException("Enable this connection before using it");
    if (!connector.path("aiChatEnabled").asBoolean() && !BUILTIN.contains(connector.path("provider").asText())) {
      throw new IllegalArgumentException("Configure your company AI endpoint in Integration Hub first");
    }
    revision(connector, input.path("connectionRevision"));
    return connector;
  }

  private Prepared prepare(String id, ObjectNode input, String actor) {
    var connector = connection(id, input);
    String prompt = input.path("prompt").asText().trim();
    boolean readOnly = AiEndpointSettings.method(connector).equals("GET");
    if ((!readOnly && prompt.isBlank()) || prompt.length() > 12000) {
      throw new IllegalArgumentException("Enter a prompt of up to 12,000 characters");
    }
    if (readOnly) prompt = "Read-only connector request";
    var built = contexts.build(input, prompt, readOnly, actor);
    var context = built.context();
    prompt = built.prompt();
    String scope = input.path("scope").asText("none");
    var identity = store.parse("{}").put("id", id).put("revision", connector.path("revision").asInt()).put("prompt", prompt);
    if (!scope.equals("none")) identity.put("specId", input.path("specId").asText());
    return new Prepared(connector, context, prompt, fingerprint(identity.toString()));
  }

  private ObjectNode priorCheck(String id) {
    try { return store.get("ai-checks", checkId(id)); }
    catch (ResponseStatusException e) {
      if (e.getStatusCode().value() == 404) return null;
      throw e;
    }
  }

  private static String checkId(String id) {
    return UUID.nameUUIDFromBytes(("ai-tools-check:" + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String fingerprint(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  private static void revision(ObjectNode record, JsonNode expected) {
    if (!expected.isIntegralNumber() || !expected.equals(record.path("revision"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The configuration or contract changed. Reload and preview again.");
    }
  }
}
