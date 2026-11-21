package com.apiintel.investigate;

import com.apiintel.activity.ActivityLog;
import com.apiintel.chat.ChatService;
import com.apiintel.chat.ConfirmationGrants;
import com.apiintel.jira.JiraService;
import com.apiintel.knowledge.KnowledgeService;
import com.apiintel.security.PlatformUserService;
import com.apiintel.splunk.SplunkService;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Bounded investigation evidence packages and the AI-chat / Jira workflows
 * around them. Evidence is capped (~40KB, 20 events), redacted via
 * {@link ActivityLog#redact} plus secret-field removal, and every item gets a
 * stable citation id E1..En the model must reference as [E#].
 */
@Service
public class InvestigateService {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;
  private static final int MAX_BYTES = 40_000;
  private static final int MAX_EVENTS = 20;
  private static final Pattern SECRET_FIELD = Pattern.compile(
      "(?i).*(secret|token|password|passwd|authorization|cookie|session|api[_-]?key|credential).*");
  private static final Pattern CITE = Pattern.compile("\\[E(\\d+)\\]");

  private final RecordStore store;
  private final PlatformUserService users;
  private final SplunkService splunk;
  private final JiraService jira;
  private final KnowledgeService knowledge;
  private final ChatService chat;
  private final ConfirmationGrants grants;
  private final ObjectMapper json = new ObjectMapper();
  private final org.springframework.jdbc.core.JdbcTemplate db;

  public InvestigateService(RecordStore store, PlatformUserService users, SplunkService splunk,
                            JiraService jira, KnowledgeService knowledge, ChatService chat,
                            ConfirmationGrants grants,
                            org.springframework.jdbc.core.JdbcTemplate db) {
    this.store = store;
    this.db = db;
    this.users = users;
    this.splunk = splunk;
    this.jira = jira;
    this.knowledge = knowledge;
    this.chat = chat;
    this.grants = grants;
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }

  public List<ObjectNode> list(String username) {
    String ownerId = ownerId(username);
    return store.list("investigations").stream()
        .filter(i -> ownerId.equals(i.path("ownerId").asText())).toList();
  }

  public ObjectNode get(String id, String username) {
    var inv = store.get("investigations", id);
    if (!ownerId(username).equals(inv.path("ownerId").asText())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Investigation not found");
    }
    return inv;
  }

  // ---------- creation ----------

  public ObjectNode create(ObjectNode input, String username) {
    String ownerId = ownerId(username);
    ObjectNode inv = J.objectNode();
    inv.put("ownerId", ownerId);
    for (String f : List.of("reportId", "snapshotId", "incidentId", "service", "correlationId", "connectionId")) {
      if (input.hasNonNull(f)) inv.put(f, input.path(f).asText());
    }
    if (input.has("window")) inv.set("window", input.get("window"));

    ArrayNode evidence = J.arrayNode();
    int[] counter = {1};
    Set<String> correlationIds = new LinkedHashSet<>();
    if (inv.hasNonNull("correlationId")) correlationIds.add(inv.path("correlationId").asText());

    // 1. metrics + resolved windows from snapshot
    if (inv.hasNonNull("snapshotId")) {
      try {
        var snap = snapshot(inv.path("snapshotId").asText(), ownerId);
        add(evidence, counter, "METRICS", "Metric changes",
            snap.path("metrics"), snap.path("id").asText());
        add(evidence, counter, "WINDOWS", "Resolved windows",
            snap.path("resolvedWindows"), snap.path("id").asText());
      } catch (RuntimeException e) {
        add(evidence, counter, "ERROR", "Snapshot unavailable", J.objectNode()
            .put("error", e.getMessage()), null);
      }
    }

    // 2. representative Splunk events (bounded, redacted)
    String connectionId = inv.path("connectionId").asText(findSplunkConnection(ownerId));
    if (!connectionId.isBlank()) {
      try {
        List<ObjectNode> events = splunk.eventsForInvestigation(connectionId, inv, MAX_EVENTS, username);
        int i = 1;
        for (ObjectNode ev : events) {
          String cid = ev.path("correlationId").asText(ev.path("_correlationId").asText(""));
          if (!cid.isBlank()) correlationIds.add(cid);
          add(evidence, counter, "EVENT", "Splunk event " + i++, ev, null);
        }
      } catch (RuntimeException ignored) {}
    }

    // 3. related Jira issues (only on the user's own Jira connection)
    String jql = buildJql(inv.path("service").asText(""), correlationIds);
    if (!jql.isBlank()) {
      String jiraConnectionId = findConnection(ownerId, "jira");
      if (!jiraConnectionId.isBlank()) {
        try {
          JsonNode res = jira.search(jiraConnectionId, jql, null, null, username);
          for (JsonNode issue : res.path("issues")) {
            add(evidence, counter, "JIRA", "Jira " + issue.path("key").asText(),
                J.objectNode().put("key", issue.path("key").asText())
                    .put("summary", issue.path("fields").path("summary").asText())
                    .put("status", issue.path("fields").path("status").path("name").asText("")),
                issue.path("key").asText());
            if (counter[0] > 30) break;
          }
        } catch (RuntimeException ignored) {}
      }
    }

    // 4. optional GitHub items — skip unless user owns a github connection
    String githubConnectionId = findConnection(ownerId, "github");
    if (!githubConnectionId.isBlank() && !inv.path("service").asText("").isBlank()) {
      // bounded: one reference evidence item noting the connection exists for manual follow-up
      add(evidence, counter, "GITHUB", "GitHub connection available",
          J.objectNode().put("connectionId", githubConnectionId), null);
    }

    // 5. similar past incidents from knowledge
    try {
      var similar = knowledge.similarIncidents(username, inv.path("service").asText(""), List.of());
      for (ObjectNode s : similar) {
        add(evidence, counter, "PAST_INCIDENT", s.path("title").asText(),
            J.objectNode().put("knowledgeId", s.path("id").asText())
                .put("kind", s.path("kind").asText()), s.path("id").asText());
        if (counter[0] > 40) break;
      }
    } catch (RuntimeException ignored) {}

    // bound the whole package (~40KB or the owner's aiContextBytes cost limit)
    int cap = contextCap(ownerId);
    while (evidence.toString().length() > cap && evidence.size() > 3) {
      evidence.remove(evidence.size() - 1);
    }

    inv.set("evidence", evidence);
    inv.put("evidenceBytes", evidence.toString().length());
    ArrayNode cids = inv.putArray("correlationIds");
    correlationIds.forEach(cids::add);
    inv.put("status", "OPEN");
    inv.put("createdAt", Instant.now().toString());
    inv = store.save("investigations", inv, username);
    knowledge.index(ownerId, "INVESTIGATION", "Investigation " + inv.path("id").asText(),
        "Investigation opened" + (inv.hasNonNull("service") ? " for " + inv.path("service").asText() : ""),
        J.arrayNode().add(knowledge.ref("INVESTIGATION", inv.path("id").asText(), null, null)),
        null);
    return inv;
  }

  /** Persist a mutated investigation (used by playbook runs). Owner-checked. */
  public ObjectNode saveForPlaybook(ObjectNode inv, String username) {
    get(inv.path("id").asText(), username);
    return store.save("investigations", inv, username);
  }

  /** Evidence byte cap: default 40KB, tightened by the user's aiContextBytes limit. */
  private int contextCap(String ownerId) {
    int cap = MAX_BYTES;
    for (var c : store.list("cost-limits")) {
      if (ownerId.equals(c.path("ownerId").asText())) {
        int v = c.path("limits").path("aiContextBytes").asInt(-1);
        if (v > 0) cap = Math.min(cap, v);
      }
    }
    return cap;
  }

  private ObjectNode snapshot(String id, String ownerId) {
    var rows = db.queryForList("SELECT * FROM report_snapshots WHERE id=?", id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot not found");
    var row = rows.get(0);
    String owner = com.apiintel.store.Rows.str(row, "owner_id");
    if (!ownerId.equals(owner)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot not found");
    }
    try {
      ObjectNode snap = J.objectNode();
      snap.put("id", id);
      snap.set("metrics", json.readTree(com.apiintel.store.Rows.str(row, "metrics")));
      snap.set("resolvedWindows", json.readTree(com.apiintel.store.Rows.str(row, "resolved_windows")));
      return snap;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Snapshot unreadable");
    }
  }

  private void add(ArrayNode evidence, int[] counter, String type, String summary,
                   JsonNode data, String ref) {
    ObjectNode e = evidence.addObject();
    e.put("id", "E" + counter[0]++);
    e.put("type", type);
    e.put("summary", summary);
    e.set("data", redactData(data));
    if (ref != null) e.put("ref", ref);
  }

  private JsonNode redactData(JsonNode node) {
    if (node == null) return J.objectNode();
    if (node.isObject()) {
      ObjectNode out = J.objectNode();
      node.fields().forEachRemaining(en -> {
        if (!SECRET_FIELD.matcher(en.getKey()).matches()) out.set(en.getKey(), redactData(en.getValue()));
      });
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = J.arrayNode();
      node.forEach(n -> out.add(redactData(n)));
      return out;
    }
    if (node.isTextual()) {
      String v = ActivityLog.redact(node.asText());
      v = v.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "<email>");
      v = v.replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "<ip>");
      return J.textNode(v);
    }
    return node;
  }

  private String buildJql(String service, Set<String> correlationIds) {
    List<String> ors = new ArrayList<>();
    if (!service.isBlank()) ors.add("text ~ \"" + service.replace("\"", "") + "\"");
    for (String c : correlationIds) {
      ors.add("text ~ \"" + c.replace("\"", "") + "\"");
    }
    return ors.isEmpty() ? "" : String.join(" OR ", ors);
  }

  private String findSplunkConnection(String ownerId) {
    return findConnection(ownerId, "splunk");
  }

  private String findConnection(String ownerId, String provider) {
    return store.list("connections").stream()
        .filter(c -> ownerId.equals(c.path("ownerId").asText()))
        .filter(c -> provider.equals(c.path("provider").asText()))
        .filter(c -> "CONNECTED".equals(c.path("state").asText()))
        .map(c -> c.path("id").asText()).findFirst().orElse("");
  }

  // ---------- chat preview/send ----------

  private String investigationPrompt(ObjectNode inv) {
    StringBuilder p = new StringBuilder();
    p.append("You are investigating an operational incident. Answer with exactly these sections:\n")
     .append("## Observations\n## Possible causes\n## Verification steps\n\n")
     .append("Cite every factual claim with its evidence id, e.g. [E1].\n\nEvidence:\n");
    for (JsonNode e : inv.path("evidence")) {
      p.append("[").append(e.path("id").asText()).append("] ")
       .append(e.path("summary").asText()).append(": ")
       .append(truncate(e.path("data").toString(), 800)).append('\n');
    }
    return p.toString();
  }

  private static String truncate(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n);
  }

  /** Preview: create the conversation, issue a confirmation over prompt+destination. */
  public ObjectNode preview(String id, ObjectNode input, String username) {
    ObjectNode inv = get(id, username);
    String connectionId = input.path("connectionId").asText("");
    if (connectionId.isBlank()) throw new IllegalArgumentException("connectionId required");
    var conversation = chat.createConversation(J.objectNode()
        .put("connectionId", connectionId).put("title", "Investigation " + id), username);
    String convId = conversation.path("id").asText();
    String prompt = investigationPrompt(inv);
    var preview = chat.preview(convId, J.objectNode()
        .put("connectionId", connectionId).put("prompt", prompt), username);
    inv.put("conversationId", convId);
    inv.put("prompt", prompt);
    store.save("investigations", inv, username);
    ObjectNode out = preview.deepCopy();
    out.put("conversationId", convId);
    out.put("evidenceCount", inv.path("evidence").size());
    return out;
  }

  /** Send: consume the confirmation and stream the chat, then run the citation check. */
  public Flux<ServerSentEvent<JsonNode>> send(String id, ObjectNode input, String username) {
    ObjectNode inv = get(id, username);
    String convId = inv.path("conversationId").asText("");
    if (convId.isBlank()) throw new IllegalArgumentException("Call preview first");
    String prompt = inv.path("prompt").asText(investigationPrompt(inv));
    ObjectNode send = J.objectNode()
        .put("connectionId", input.path("connectionId").asText(""))
        .put("prompt", prompt)
        .put("confirmationId", input.path("confirmationId").asText(""));
    if (input.hasNonNull("transferHistory")) send.put("transferHistory", input.path("transferHistory").asBoolean());
    StringBuilder collected = new StringBuilder();
    Set<String> evidenceIds = new LinkedHashSet<>();
    inv.path("evidence").forEach(e -> evidenceIds.add(e.path("id").asText()));
    return chat.send(convId, send, username)
        .doOnNext(e -> {
          if ("delta".equals(e.event())) collected.append(e.data().path("text").asText(""));
        })
        .doOnComplete(() -> {
          try {
            ObjectNode check = citationCheck(collected.toString(), evidenceIds);
            var fresh = store.get("investigations", id);
            fresh.set("citationCheck", check);
            store.save("investigations", fresh, username);
          } catch (RuntimeException ignored) {}
        });
  }

  /** Post-check: every [E#] must exist; Observations sentences must carry a citation. */
  public ObjectNode citationCheck(String text, Set<String> evidenceIds) {
    ObjectNode out = J.objectNode();
    ArrayNode bad = out.putArray("unknownCitations");
    var m = CITE.matcher(text);
    while (m.find()) {
      String ref = "E" + m.group(1);
      if (!evidenceIds.contains(ref) && bad.toString().indexOf(ref) < 0) bad.add(ref);
    }
    ArrayNode uncited = out.putArray("uncitedObservationSentences");
    String observations = text;
    int idx = text.indexOf("Observations");
    if (idx >= 0) {
      int end = text.indexOf("Possible causes", idx);
      observations = end > idx ? text.substring(idx + "Observations".length(), end) : text.substring(idx);
    } else {
      observations = "";
    }
    for (String sentence : observations.split("[.!?\\n]+")) {
      String s = sentence.trim();
      if (s.isEmpty() || s.startsWith("#")) continue;
      if (!CITE.matcher(s).find()) uncited.add(s.length() > 160 ? s.substring(0, 160) : s);
    }
    out.put("ok", bad.isEmpty() && uncited.isEmpty());
    return out;
  }

  // ---------- jira draft ----------

  public ObjectNode jiraDraft(String id, ObjectNode input, String username) {
    ObjectNode inv = get(id, username);
    String connectionId = input.path("connectionId").asText(findConnection(inv.path("ownerId").asText(), "jira"));
    if (connectionId.isBlank()) throw new IllegalArgumentException("No Jira connection");
    ObjectNode fields = J.objectNode();
    String service = inv.path("service").asText("service");
    fields.put("summary", "Investigation: " + service + " incident " + inv.path("id").asText().substring(0, 8));
    ObjectNode description = J.objectNode();
    description.put("type", "doc").put("version", 1);
    ArrayNode content = description.putArray("content");
    content.add(paragraph("Impact: " + inv.path("service").asText("unknown")
        + " during " + windowText(inv)));
    content.add(paragraph("Report: " + reportLink(inv)));
    content.add(paragraph("Error signatures: " + signatures(inv)));
    content.add(paragraph("Investigation summary: " + citationOrEvidence(inv)));
    fields.set("description", description);
    if (input.has("fields")) fields.setAll((ObjectNode) input.get("fields"));
    var proposal = jira.proposeIssue(connectionId, null, fields, username);
    inv.put("jiraProposalId", proposal.path("id").asText(""));
    store.save("investigations", inv, username);
    return proposal;
  }

  private ObjectNode paragraph(String text) {
    ObjectNode p = J.objectNode().put("type", "paragraph");
    ArrayNode c = p.putArray("content");
    c.addObject().put("type", "text").put("text", text);
    return p;
  }

  private String windowText(ObjectNode inv) {
    var w = inv.path("window");
    return w.isMissingNode() ? "current window" : w.toString();
  }

  private String reportLink(ObjectNode inv) {
    return inv.hasNonNull("reportId") ? "/reports/" + inv.path("reportId").asText() : "n/a";
  }

  private String signatures(ObjectNode inv) {
    List<String> sigs = new ArrayList<>();
    inv.path("evidence").forEach(e -> {
      if ("EVENT".equals(e.path("type").asText())) {
        String ex = e.path("data").path("exception").asText("");
        if (!ex.isBlank() && !sigs.contains(ex)) sigs.add(ex);
      }
    });
    return sigs.isEmpty() ? "none" : String.join(", ", sigs);
  }

  private String citationOrEvidence(ObjectNode inv) {
    StringBuilder s = new StringBuilder();
    int n = 0;
    for (JsonNode e : inv.path("evidence")) {
      if (n++ >= 5) break;
      s.append("[").append(e.path("id").asText()).append("] ")
       .append(e.path("summary").asText()).append(". ");
    }
    return s.toString();
  }

  // ---------- resolve ----------

  public ObjectNode resolve(String id, ObjectNode input, String username) {
    ObjectNode inv = get(id, username);
    String resolution = input.path("resolution").asText("");
    if (resolution.isBlank()) throw new IllegalArgumentException("Provide a resolution");
    inv.put("status", "RESOLVED");
    inv.put("resolution", resolution);
    inv.put("resolvedAt", Instant.now().toString());
    inv = store.save("investigations", inv, username);
    ArrayNode refs = J.arrayNode();
    refs.add(knowledge.ref("INVESTIGATION", id, null, null));
    if (inv.hasNonNull("reportId")) refs.add(knowledge.ref("REPORT", inv.path("reportId").asText(), null, null));
    knowledge.index(inv.path("ownerId").asText(), "INCIDENT_RESOLUTION",
        "Resolution: " + inv.path("service").asText(inv.path("id").asText()), resolution,
        refs, KnowledgeService.sha(resolution));
    return inv;
  }
}
