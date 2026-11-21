package com.apiintel.knowledge;

import com.apiintel.security.PlatformUserService;
import com.apiintel.store.Rows;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Searchable knowledge: report summaries, incident resolutions, investigations
 * and AI chat history. SQLite uses an FTS5 external-content table kept in sync
 * by triggers; on databases without FTS5 (the H2 migration mirror) search
 * transparently falls back to LIKE.
 *
 * <p>Items carry {@code source_refs} ({type,id,revision,hash}); when a source
 * changes or is deleted the derived item is invalidated ({@code valid=0}), and
 * retrieval re-checks ownership and freshness, returning {item, stale, reason}.
 */
@Service
public class KnowledgeService {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;
  public static final Set<String> KINDS = Set.of(
      "REPORT_SUMMARY", "INCIDENT_RESOLUTION", "INVESTIGATION", "CHAT_MESSAGE");

  private final JdbcTemplate db;
  private final PlatformUserService users;
  private final ObjectMapper json = new ObjectMapper();
  private final boolean fts;
  private final int rawEventsDays;
  private final int snapshotsDays;

  public KnowledgeService(JdbcTemplate db, PlatformUserService users,
                          @Value("${platform.retention.raw-events-days:7}") int rawEventsDays,
                          @Value("${platform.retention.snapshots-days:90}") int snapshotsDays) {
    this.db = db;
    this.users = users;
    this.rawEventsDays = rawEventsDays;
    this.snapshotsDays = snapshotsDays;
    this.fts = detectFts(db);
  }

  private static boolean detectFts(JdbcTemplate db) {
    try {
      db.execute("SELECT 1 FROM knowledge_fts LIMIT 1");
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }

  // ---------- indexing ----------

  /** Insert a knowledge item; derived items carry source refs + dependency hash. */
  public ObjectNode index(String ownerId, String kind, String title, String body,
                          ArrayNode sourceRefs, String dependencyHash) {
    if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unknown knowledge kind");
    String id = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    db.update("INSERT INTO knowledge_items(id,owner_id,kind,title,body,source_refs,dependency_hash,"
        + "valid,created_at,updated_at) VALUES(?,?,?,?,?,?,?,1,?,?)",
        id, ownerId, kind, title == null ? "" : title, body == null ? "" : body,
        sourceRefs == null ? "[]" : sourceRefs.toString(),
        dependencyHash == null ? "" : dependencyHash, now, now);
    return J.objectNode().put("id", id).put("kind", kind);
  }

  /** Index a chat message so knowledge search covers AI chat history. */
  public void indexChatMessage(String ownerId, String conversationId, String messageId,
                               String role, String content) {
    ArrayNode refs = J.arrayNode();
    refs.add(ref("CHAT_MESSAGE", messageId, null, sha(content)));
    refs.add(ref("CHAT_CONVERSATION", conversationId, null, null));
    index(ownerId, "CHAT_MESSAGE", "chat " + role + " in " + conversationId,
        content, refs, sha(content));
  }

  public ObjectNode ref(String type, String id, String revision, String hash) {
    ObjectNode r = J.objectNode().put("type", type).put("id", id == null ? "" : id);
    if (revision != null) r.put("revision", revision);
    if (hash != null) r.put("hash", hash);
    return r;
  }

  public static String sha(String text) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest((text == null ? "" : text)
              .getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    } catch (Exception e) {
      return "";
    }
  }

  // ---------- invalidation ----------

  /** Mark every derived item whose source_refs mention the source invalid. */
  public int invalidate(String sourceType, String sourceId) {
    int n = 0;
    for (var row : db.queryForList("SELECT id,source_refs FROM knowledge_items WHERE valid=1")) {
      String refs = Rows.str(row, "source_refs");
      try {
        for (JsonNode r : json.readTree(refs)) {
          if (sourceType.equals(r.path("type").asText()) && sourceId.equals(r.path("id").asText())) {
            db.update("UPDATE knowledge_items SET valid=0,updated_at=? WHERE id=?",
                Instant.now().toString(), Rows.str(row, "id"));
            n++;
            break;
          }
        }
      } catch (Exception ignored) {}
    }
    return n;
  }

  /** Invalidate derived items when a hashed source's content changed. */
  public int invalidateByHash(String sourceType, String sourceId, String currentHash) {
    int n = 0;
    for (var row : db.queryForList("SELECT id,source_refs FROM knowledge_items WHERE valid=1")) {
      try {
        for (JsonNode r : json.readTree(Rows.str(row, "source_refs"))) {
          if (sourceType.equals(r.path("type").asText()) && sourceId.equals(r.path("id").asText())
              && !currentHash.equals(r.path("hash").asText(currentHash))) {
            db.update("UPDATE knowledge_items SET valid=0,updated_at=? WHERE id=?",
                Instant.now().toString(), Rows.str(row, "id"));
            n++;
            break;
          }
        }
      } catch (Exception ignored) {}
    }
    return n;
  }

  // ---------- search ----------

  /** Owner-scoped FTS (bm25 ranking on SQLite) or LIKE fallback. */
  public List<ObjectNode> search(String username, String q, List<String> kinds) {
    String ownerId = ownerId(username);
    List<ObjectNode> out = new ArrayList<>();
    String kindFilter = kinds == null || kinds.isEmpty() ? null
        : " AND kind IN (" + String.join(",", kinds.stream().map(k -> "'" + k.replace("'", "") + "'").toList()) + ")";
    if (fts && q != null && !q.isBlank()) {
      String sql = "SELECT k.*, bm25(knowledge_fts) AS rank FROM knowledge_fts f"
          + " JOIN knowledge_items k ON k.rowid = f.rowid"
          + " WHERE knowledge_fts MATCH ? AND k.owner_id=?" + (kindFilter == null ? "" : kindFilter)
          + " ORDER BY rank LIMIT 50";
      for (var row : db.queryForList(sql, ftsQuery(q), ownerId)) out.add(rowJson(row));
    } else {
      String sql = "SELECT * FROM knowledge_items WHERE owner_id=?"
          + (kindFilter == null ? "" : kindFilter)
          + (q == null || q.isBlank() ? "" : " AND (LOWER(title) LIKE ? OR LOWER(body) LIKE ?)")
          + " ORDER BY updated_at DESC LIMIT 50";
      if (q == null || q.isBlank()) {
        for (var row : db.queryForList(sql, ownerId)) out.add(rowJson(row));
      } else {
        String like = "%" + q.toLowerCase() + "%";
        for (var row : db.queryForList(sql, ownerId, like, like)) out.add(rowJson(row));
      }
    }
    return out;
  }

  /** Escape a user query into an FTS5 MATCH expression (AND of quoted terms). */
  static String ftsQuery(String q) {
    String[] terms = q.trim().split("\\s+");
    List<String> quoted = new ArrayList<>();
    for (String t : terms) {
      if (t.isBlank()) continue;
      quoted.add("\"" + t.replace("\"", "") + "\"");
    }
    return quoted.isEmpty() ? "\"\"" : String.join(" AND ", quoted);
  }

  /** Similar past incidents: bm25 over error signatures + service. */
  public List<ObjectNode> similarIncidents(String username, String service, List<String> signatures) {
    StringBuilder q = new StringBuilder(service == null ? "" : service);
    if (signatures != null) for (String s : signatures) q.append(' ').append(s);
    return search(username, q.toString().trim(), List.of("INCIDENT_RESOLUTION", "INVESTIGATION"));
  }

  /** Retrieve with staleness re-check: returns {item, stale, reason}. */
  public ObjectNode getChecked(String id, String username) {
    var rows = db.queryForList("SELECT * FROM knowledge_items WHERE id=?", id);
    ObjectNode out = J.objectNode();
    if (rows.isEmpty()) {
      out.putNull("item");
      out.put("stale", true);
      out.put("reason", "not found");
      return out;
    }
    ObjectNode item = rowJson(rows.get(0));
    out.set("item", item);
    if (!ownerId(username).equals(item.path("ownerId").asText())) {
      out.put("stale", true);
      out.put("reason", "access denied");
      return out;
    }
    if (item.path("valid").asInt() != 1) {
      out.put("stale", true);
      out.put("reason", "source changed or deleted");
      return out;
    }
    out.put("stale", false);
    out.putNull("reason");
    return out;
  }

  private ObjectNode rowJson(java.util.Map<String, Object> row) {
    ObjectNode o = J.objectNode();
    o.put("id", Rows.str(row, "id"));
    o.put("ownerId", Rows.str(row, "owner_id"));
    o.put("kind", Rows.str(row, "kind"));
    o.put("title", Rows.str(row, "title"));
    o.put("body", Rows.str(row, "body"));
    try { o.set("sourceRefs", json.readTree(Rows.str(row, "source_refs"))); } catch (Exception e) {
      o.set("sourceRefs", J.arrayNode());
    }
    o.put("dependencyHash", Rows.str(row, "dependency_hash"));
    o.put("valid", Rows.num(row, "valid"));
    o.put("createdAt", Rows.str(row, "created_at"));
    o.put("updatedAt", Rows.str(row, "updated_at"));
    if (Rows.get(row, "rank") != null) o.put("rank", ((Number) Rows.get(row, "rank")).doubleValue());
    return o;
  }

  // ---------- retention ----------

  /** Purge snapshots and raw representative events past retention. */
  @Scheduled(fixedDelayString = "3600000", initialDelayString = "60000")
  public void purge() {
    Instant snapCutoff = Instant.now().minusSeconds(snapshotsDays * 86400L);
    db.update("DELETE FROM report_snapshots WHERE created_at < ?", snapCutoff.toString());
  }

  public boolean ftsEnabled() { return fts; }
}
