package com.apiintel.chat;

import com.apiintel.diagnostics.ApiRequestLog;
import com.apiintel.integrations.ApprovedHosts;
import com.apiintel.integrations.ConnectionService;
import com.apiintel.integrations.IntegrationService;
import com.apiintel.security.PlatformUserService;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Unified AI chat: owner-scoped conversations over personal connections and
 * authorized company AI definitions, routed through the ChatAdapter registry.
 * Conversations bind to a connection_id; switching providers requires a
 * history-transfer preview whose confirmation fingerprint covers the history.
 */
@Service
public class ChatService {

  private static final int HISTORY_LIMIT = 20;
  private static final int HISTORY_CHARS = 60_000;

  private final JdbcTemplate db;
  private final RecordStore store;
  private final PlatformUserService users;
  private final ConnectionService connections;
  private final IntegrationService integrations;
  private final ChatAdapters adapters;
  private final ChatContextService contexts;
  private final ConfirmationGrants grants;
  private final com.apiintel.oauth.TokenRefresher refresher;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.apiintel.knowledge.KnowledgeService knowledge;
  private final Map<String, Disposable> inflight = new ConcurrentHashMap<>();
  private final Map<String, String> inflightMessage = new ConcurrentHashMap<>();

  public ChatService(JdbcTemplate db, RecordStore store, PlatformUserService users,
      ConnectionService connections, IntegrationService integrations, ChatAdapters adapters,
      ChatContextService contexts, ConfirmationGrants grants,
      com.apiintel.oauth.TokenRefresher refresher) {
    this.db = db;
    this.store = store;
    this.users = users;
    this.connections = connections;
    this.integrations = integrations;
    this.adapters = adapters;
    this.contexts = contexts;
    this.grants = grants;
    this.refresher = refresher;
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }

  // ---------------------------------------------------------------- providers

  public List<ObjectNode> providers(String username) {
    String oid = ownerId(username);
    var out = new ArrayList<ObjectNode>();
    for (ObjectNode connection : store.list("connections")) {
      if (!oid.equals(connection.path("ownerId").asText())) continue;
      if (!"CONNECTED".equals(connection.path("state").asText())) continue;
      var adapter = adapters.forConnection(connection);
      out.add(providerItem(connection, adapter, false));
    }
    for (ObjectNode definition : store.list("integrations")) {
      if (!definition.path("aiChatEnabled").asBoolean()
          && !Set.of("openai", "azure", "anthropic", "ollama", "devin", "github-copilot", "copilot")
              .contains(definition.path("provider").asText())) continue;
      try {
        integrations.authorize(definition.path("id").asText());
      } catch (RuntimeException e) {
        continue;
      }
      var adapter = adapters.forConnection(definition);
      out.add(providerItem(definition, adapter, true));
    }
    return out;
  }

  private ObjectNode providerItem(ObjectNode connection, ChatAdapter adapter, boolean definition) {
    var item = store.parse("{}");
    item.put("connectionId", connection.path("id").asText());
    item.put("provider", connection.path("provider").asText());
    item.put("name", connection.path("name").asText());
    item.put("account", connection.path("verifiedIdentity").asText(""));
    item.put("host", connection.path("siteHost").asText(
      hostOf(connection.path("baseUrl").asText(""))));
    item.put("state", connection.path("state").asText(definition ? "DEFINITION" : "CONNECTED"));
    item.put("definition", definition);
    item.set("capabilities", adapter.capabilities(connection));
    return item;
  }

  private static String hostOf(String baseUrl) {
    try {
      var host = URI.create(baseUrl).getHost();
      return host == null ? "" : host;
    } catch (Exception e) {
      return "";
    }
  }

  // ------------------------------------------------------------ conversations

  public List<Map<String, Object>> conversations(String username) {
    return db.queryForList(
      "SELECT * FROM chat_conversations WHERE owner_id=? ORDER BY updated_at DESC LIMIT 200",
      ownerId(username));
  }

  public ObjectNode createConversation(ObjectNode input, String username) {
    String oid = ownerId(username);
    String connectionId = input.path("connectionId").asText("");
    String provider = "";
    if (!connectionId.isBlank()) {
      var runtime = resolveConnection(connectionId, username);
      provider = runtime.path("provider").asText();
    }
    String id = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    String title = input.path("title").asText("");
    db.update(
      "INSERT INTO chat_conversations(id,owner_id,connection_id,provider,title,created_at,updated_at,provider_state) VALUES(?,?,?,?,?,?,?,?)",
      id, oid, connectionId.isBlank() ? null : connectionId, provider,
      title.isBlank() ? "New chat" : title, now, now, "{}");
    return conversationView(id);
  }

  public ObjectNode renameConversation(String id, ObjectNode input, String username) {
    ownConversation(id, username);
    String title = input.path("title").asText("").trim();
    if (title.isBlank() || title.length() > 200) throw new IllegalArgumentException("Enter a title");
    db.update("UPDATE chat_conversations SET title=?, updated_at=? WHERE id=?",
      title, Instant.now().toString(), id);
    return conversationView(id);
  }

  public void deleteConversation(String id, String username) {
    ownConversation(id, username);
    cancel(id, username);
    db.update("DELETE FROM chat_messages WHERE conversation_id=?", id);
    db.update("DELETE FROM chat_conversations WHERE id=?", id);
    if (knowledge != null) {
      try { knowledge.invalidate("CHAT_CONVERSATION", id); } catch (RuntimeException ignored) {}
    }
  }

  public List<Map<String, Object>> messages(String conversationId, String username) {
    ownConversation(conversationId, username);
    return db.queryForList(
      "SELECT * FROM chat_messages WHERE conversation_id=? ORDER BY seq LIMIT 500", conversationId);
  }

  // ----------------------------------------------------------------- preview

  public ObjectNode preview(String conversationId, ObjectNode input, String username) {
    var conversation = ownConversation(conversationId, username);
    String targetConnectionId = input.path("connectionId").asText(
      str(conversation.get("CONNECTION_ID") == null ? conversation.get("connection_id") : conversation.get("CONNECTION_ID")));
    if (targetConnectionId.isBlank()) throw new IllegalArgumentException("Choose a provider first");
    var connector = resolveConnection(targetConnectionId, username);
    String prompt = input.path("prompt").asText("").trim();
    if (prompt.isBlank() || prompt.length() > 12000) {
      throw new IllegalArgumentException("Enter a prompt of up to 12,000 characters");
    }
    boolean readOnly = !connector.path("aiChatEnabled").asBoolean()
      && "GET".equalsIgnoreCase(connector.path("aiMethod").asText("POST"));
    var built = contexts.build(input, prompt, readOnly, username);
    prompt = built.prompt();

    String boundConnectionId = str(conversation.get("connection_id") == null
      ? conversation.get("CONNECTION_ID") : conversation.get("connection_id"));
    boolean switching = !boundConnectionId.isBlank() && !boundConnectionId.equals(targetConnectionId);
    var history = ChatSupport.JSON.createArrayNode();
    if (switching) {
      if (!input.path("transferHistory").asBoolean(false)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Sending with a different provider requires transferHistory confirmation");
      }
      for (var turn : history(conversationId)) {
        var item = history.addObject();
        item.put("role", turn.path("role").asText());
        item.put("content", turn.path("content").asText());
      }
    }
    var identity = store.parse("{}");
    identity.put("conversationId", conversationId);
    identity.put("connectionId", targetConnectionId);
    identity.put("revision", connector.path("revision").asInt());
    identity.put("prompt", prompt);
    identity.set("context", built.context());
    identity.set("history", history);
    String fingerprint = ConfirmationGrants.fingerprint(identity.toString());
    String confirmationId = grants.issue(username, fingerprint);

    var adapter = adapters.forConnection(connector);
    var result = store.parse("{}");
    result.put("confirmationId", confirmationId);
    result.put("destination", hostOf(connector.path("baseUrl").asText("")));
    result.put("provider", connector.path("provider").asText());
    result.put("account", connector.path("verifiedIdentity").asText(""));
    result.put("prompt", prompt);
    result.set("context", built.context());
    result.set("capabilities", adapter.capabilities(connector));
    if (switching) result.set("historyTransfer", history);
    return result;
  }

  // -------------------------------------------------------------------- send

  public Flux<ServerSentEvent<JsonNode>> send(String conversationId, ObjectNode input, String username) {
    var conversation = ownConversation(conversationId, username);
    String targetConnectionId = input.path("connectionId").asText(
      str(conversation.get("connection_id") == null ? conversation.get("CONNECTION_ID") : conversation.get("connection_id")));
    if (targetConnectionId.isBlank()) throw new IllegalArgumentException("Choose a provider first");
    var connector = resolveConnection(targetConnectionId, username);
    String prompt = input.path("prompt").asText("").trim();
    if (prompt.isBlank() || prompt.length() > 12000) {
      throw new IllegalArgumentException("Enter a prompt of up to 12,000 characters");
    }
    boolean readOnly = !connector.path("aiChatEnabled").asBoolean()
      && "GET".equalsIgnoreCase(connector.path("aiMethod").asText("POST"));
    var built = contexts.build(input, prompt, readOnly, username);
    prompt = built.prompt();

    String boundConnectionId = str(conversation.get("connection_id") == null
      ? conversation.get("CONNECTION_ID") : conversation.get("connection_id"));
    boolean switching = !boundConnectionId.isBlank() && !boundConnectionId.equals(targetConnectionId);
    var historyArray = ChatSupport.JSON.createArrayNode();
    List<ObjectNode> history = history(conversationId);
    if (switching) {
      if (!input.path("transferHistory").asBoolean(false)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Sending with a different provider requires transferHistory confirmation");
      }
      for (var turn : history) historyArray.add(turn);
    }
    var identity = store.parse("{}");
    identity.put("conversationId", conversationId);
    identity.put("connectionId", targetConnectionId);
    identity.put("revision", connector.path("revision").asInt());
    identity.put("prompt", prompt);
    identity.set("context", built.context());
    identity.set("history", historyArray);
    grants.consume(input.path("confirmationId").asText(), username,
      ConfirmationGrants.fingerprint(identity.toString()));

    String oid = ownerId(username);
    var adapter = adapters.forConnection(connector);
    var caps = adapter.capabilities(connector);

    // Bind the conversation to the connection on first send / provider switch.
    db.update("UPDATE chat_conversations SET connection_id=?, provider=?, updated_at=? WHERE id=?",
      targetConnectionId, connector.path("provider").asText(), Instant.now().toString(), conversationId);

    appendMessage(conversationId, oid, "user", prompt, "complete", null);
    String currentTitle = str(conversation.get("title") == null ? conversation.get("TITLE") : conversation.get("title"));
    if ("New chat".equals(currentTitle) || currentTitle.isBlank()) {
      db.update("UPDATE chat_conversations SET title=? WHERE id=?",
        prompt.length() > 60 ? prompt.substring(0, 60) : prompt, conversationId);
    }
    String assistantId = appendMessage(conversationId, oid, "assistant", "", "streaming",
      meta -> {
        meta.put("provider", connector.path("provider").asText());
        meta.put("connectionId", targetConnectionId);
        meta.put("account", connector.path("verifiedIdentity").asText(""));
      });
    final String assistantMessageId = assistantId;
    var providerState = store.parse(conversationState(conversation));
    var cancelled = new AtomicBoolean(false);
    var request = new ChatRequest(connector, prompt,
      caps.path("multiTurn").asBoolean() ? history : List.of(),
      providerState, oid, username, cancelled::get);

    var text = new StringBuilder();
    var taskMeta = ChatSupport.JSON.createObjectNode();
    var requestId = ApiRequestLog.requestId();
    Flux<ChatEvent> stream;
    try {
      refresher.ensureFresh(connector, username);
      stream = adapter.stream(request).subscribeOn(Schedulers.boundedElastic());
    } catch (RuntimeException e) {
      stream = Flux.error(e);
    }
    final var outbound = stream;
    return Flux.create(emitter -> {
      var metaData = ChatSupport.JSON.createObjectNode();
      metaData.put("messageId", assistantMessageId);
      metaData.put("provider", connector.path("provider").asText());
      metaData.put("account", connector.path("verifiedIdentity").asText(""));
      emitter.next(ServerSentEvent.<JsonNode>builder(metaData).event("meta").build());
      Disposable sub = outbound
        .doOnCancel(() -> {
          cancelled.set(true);
          finishMessage(assistantMessageId, text.toString(), "cancelled", taskMeta);
        })
        .subscribe(event -> {
            if ("delta".equals(event.type())) {
              text.append(event.data().path("text").asText(""));
            }
            if ("task".equals(event.type())) {
              taskMeta.set("task", event.data());
            }
            emitter.next(ServerSentEvent.<JsonNode>builder(event.data()).event(event.type()).build());
          },
          error -> {
            var chatError = ChatSupport.error(error);
            int status = ChatSupport.statusOf(error.getMessage() == null ? "" : error.getMessage());
            if (status == 401 || status == 403) {
              connections.markReconnect(targetConnectionId, username, error.getMessage());
            }
            taskMeta.set("error", chatError.data());
            finishMessage(assistantMessageId, text.toString(), "error", taskMeta);
            emitter.next(ServerSentEvent.<JsonNode>builder(chatError.data()).event("error").build());
            emitter.complete();
          },
          () -> {
            finishMessage(assistantMessageId, text.toString(), "complete", taskMeta);
            db.update("UPDATE chat_conversations SET provider_state=?, updated_at=? WHERE id=?",
              providerState.toString(), Instant.now().toString(), conversationId);
            var done = ChatSupport.JSON.createObjectNode();
            done.put("messageId", assistantMessageId);
            done.put("requestId", requestId);
            emitter.next(ServerSentEvent.<JsonNode>builder(done).event("done").build());
            emitter.complete();
          });
      inflight.put(conversationId, sub);
      inflightMessage.put(conversationId, assistantMessageId);
      emitter.onDispose(() -> {
        inflight.remove(conversationId, sub);
        inflightMessage.remove(conversationId, assistantMessageId);
      });
    });
  }

  public ObjectNode cancel(String conversationId, String username) {
    ownConversation(conversationId, username);
    var sub = inflight.remove(conversationId);
    if (sub != null) sub.dispose();
    var result = store.parse("{}").put("cancelled", sub != null);
    return result;
  }

  // ------------------------------------------------------------------ helpers

  private ObjectNode resolveConnection(String connectionId, String username) {
    try {
      var connection = connections.getOwned(connectionId, username);
      return connections.runtimeConfig(connection);
    } catch (ResponseStatusException e) {
      if (e.getStatusCode() != HttpStatus.NOT_FOUND) throw e;
      integrations.authorize(connectionId);
      return integrations.config(connectionId);
    } catch (AccessDeniedException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().contains("User not found")) throw e;
      integrations.authorize(connectionId);
      return integrations.config(connectionId);
    }
  }

  private Map<String, Object> ownConversation(String id, String username) {
    var rows = db.queryForList("SELECT * FROM chat_conversations WHERE id=?", id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
    var conversation = rows.get(0);
    String owner = str(conversation.get("owner_id") == null ? conversation.get("OWNER_ID") : conversation.get("owner_id"));
    if (!ownerId(username).equals(owner)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
    }
    return conversation;
  }

  private ObjectNode conversationView(String id) {
    var conversation = db.queryForList("SELECT * FROM chat_conversations WHERE id=?", id).get(0);
    var view = store.parse("{}");
    conversation.forEach((k, v) -> {
      String key = k.toLowerCase(Locale.ROOT);
      if (v != null) view.putPOJO(key, v);
    });
    return view;
  }

  private List<ObjectNode> history(String conversationId) {
    var rows = db.queryForList(
      "SELECT role,content FROM chat_messages WHERE conversation_id=? AND status IN ('complete','cancelled') ORDER BY seq DESC LIMIT ?",
      conversationId, HISTORY_LIMIT);
    var out = new ArrayList<ObjectNode>();
    int chars = 0;
    for (int i = rows.size() - 1; i >= 0; i--) {
      var row = rows.get(i);
      String role = str(row.get("ROLE") == null ? row.get("role") : row.get("ROLE"));
      String content = str(row.get("CONTENT") == null ? row.get("content") : row.get("CONTENT"));
      if (!Set.of("user", "assistant", "system").contains(role) || content.isBlank()) continue;
      if (chars + content.length() > HISTORY_CHARS) break;
      var turn = ChatSupport.JSON.createObjectNode();
      turn.put("role", role);
      turn.put("content", content);
      out.add(turn);
      chars += content.length();
    }
    return out;
  }

  private String appendMessage(String conversationId, String ownerId, String role, String content,
      String status, java.util.function.Consumer<ObjectNode> meta) {
    Integer seq = db.queryForObject(
      "SELECT COALESCE(MAX(seq),0)+1 FROM chat_messages WHERE conversation_id=?", Integer.class, conversationId);
    String id = UUID.randomUUID().toString();
    var metaNode = ChatSupport.JSON.createObjectNode();
    if (meta != null) meta.accept(metaNode);
    db.update(
      "INSERT INTO chat_messages(id,conversation_id,owner_id,seq,role,content,provider,connection_id,account,status,meta,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
      id, conversationId, ownerId, seq, role, content,
      metaNode.path("provider").asText(null), metaNode.path("connectionId").asText(null),
      metaNode.path("account").asText(null), status, metaNode.toString(), Instant.now().toString());
    return id;
  }

  private void finishMessage(String id, String content, String status, ObjectNode extraMeta) {
    var rows = db.queryForList("SELECT meta FROM chat_messages WHERE id=?", id);
    String metaText = rows.isEmpty() ? ""
      : str(rows.get(0).get("META") == null ? rows.get(0).get("meta") : rows.get(0).get("META"));
    var meta = metaText.isBlank() ? ChatSupport.JSON.createObjectNode() : store.parse(metaText);
    if (extraMeta != null) meta.setAll(extraMeta);
    db.update("UPDATE chat_messages SET content=?, status=?, meta=? WHERE id=?",
      content, status, meta.toString(), id);
    if (knowledge != null && "complete".equals(status) && !content.isBlank()) {
      try {
        var rows2 = db.queryForList("SELECT owner_id,conversation_id,role FROM chat_messages WHERE id=?", id);
        if (!rows2.isEmpty()) {
          var row = rows2.get(0);
          String owner = str(row.get("owner_id") == null ? row.get("OWNER_ID") : row.get("owner_id"));
          String convId = str(row.get("conversation_id") == null ? row.get("CONVERSATION_ID") : row.get("conversation_id"));
          String role = str(row.get("role") == null ? row.get("ROLE") : row.get("role"));
          knowledge.indexChatMessage(owner, convId, id, role, content);
        }
      } catch (RuntimeException ignored) {}
    }
  }

  private String conversationState(Map<String, Object> conversation) {
    Object state = conversation.get("provider_state") == null
      ? conversation.get("PROVIDER_STATE") : conversation.get("provider_state");
    String text = str(state);
    return text.isBlank() ? "{}" : text;
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
