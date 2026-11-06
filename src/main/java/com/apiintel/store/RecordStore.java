package com.apiintel.store;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecordStore {

  private final JdbcTemplate db;
  private final ObjectMapper json;
  public static final Set<String> KINDS = Set.of(
    "environments",
    "specs",
    "collections",
    "history",
    "stories",
    "team",
    "sprints",
    "leaves",
    "holidays",
    "tests",
    "suites",
    "plans",
    "runs",
    "rules",
    "diagrams",
    "integrations",
    "events",
    "ai-drafts",
    "governance-runs"
  );

  public RecordStore(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public ObjectNode parse(String s) {
    try {
      return (ObjectNode) json.readTree(s);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON object", e);
    }
  }

  public List<ObjectNode> list(String kind) {
    check(kind);
    return db.query(
      "SELECT payload FROM records WHERE kind=? ORDER BY updated_at DESC",
      (rs, n) -> parse(rs.getString(1)),
      kind
    );
  }

  public ObjectNode get(String kind, String id) {
    check(kind);
    return db.query(
        "SELECT payload FROM records WHERE kind=? AND id=?",
        (rs, row) -> parse(rs.getString(1)), kind, id
      ).stream().findFirst()
      .orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found")
      );
  }

  @Transactional
  public ObjectNode save(String kind, ObjectNode data, String actor) {
    check(kind);
    var copy = data.deepCopy();
    String id = copy.path("id").asText(UUID.randomUUID().toString());
    if (
      copy.toString().length() > 1_000_000
    ) throw new IllegalArgumentException("Record too large");
    var old = db.queryForList(
      "SELECT revision,kind FROM records WHERE id=?",
      id
    );
    int rev = 1;
    if (!old.isEmpty()) {
      if (
        !kind.equals(old.get(0).get("KIND"))
      ) throw new IllegalArgumentException("Record kind mismatch");
      rev = ((Number) old.get(0).get("REVISION")).intValue() + 1;
      if (
        copy.path("revision").asInt() != rev - 1
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Record changed; reload before saving"
      );
    }
    copy.put("id", id);
    copy.put("revision", rev);
    Instant now = Instant.now();
    var timestamp = java.sql.Timestamp.from(now);
    copy.put("updatedAt", now.toString());
    if (rev == 1) db.update(
      "INSERT INTO records (id,kind,revision,payload,updated_at) VALUES(?,?,?,?,?)",
      id,
      kind,
      rev,
      copy.toString(),
      timestamp
    );
    else if (
      db.update(
        "UPDATE records SET revision=?,payload=?,updated_at=? WHERE id=? AND revision=?",
        rev,
        copy.toString(),
        timestamp,
        id,
        rev - 1
      ) != 1
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Concurrent update"
    );
    db.update(
      "INSERT INTO revisions (id,revision,payload,changed_at) VALUES(?,?,?,?)",
      id,
      rev,
      copy.toString(),
      timestamp
    );
    audit(actor, "save:" + kind, id);
    return copy;
  }

  @Transactional
  public void delete(String kind, String id, String actor) {
    get(kind, id);
    db.update("DELETE FROM records WHERE id=? AND kind=?", id, kind);
    audit(actor, "delete:" + kind, id);
  }

  public List<ObjectNode> versions(String kind, String id) {
    get(kind, id);
    return db.query(
      "SELECT payload FROM revisions WHERE id=? ORDER BY revision DESC",
      (rs, n) -> parse(rs.getString(1)),
      id
    );
  }

  public ObjectNode restore(
    String kind,
    String id,
    int revision,
    String actor
  ) {
    var current = get(kind, id);
    var old = versions(kind, id)
      .stream()
      .filter(v -> v.path("revision").asInt() == revision)
      .findFirst()
      .orElseThrow(() -> new IllegalArgumentException("Unknown revision"));
    old.put("revision", current.path("revision").asInt());
    return save(kind, old, actor);
  }

  public void audit(String actor, String action, String subject) {
    db.update(
      "INSERT INTO audit(actor,action,subject) VALUES(?,?,?)",
      actor,
      action,
      subject
    );
  }

  public List<Map<String, Object>> audit() {
    return db.queryForList("SELECT * FROM audit ORDER BY id DESC LIMIT 300");
  }

  private void check(String kind) {
    if (!KINDS.contains(kind)) throw new IllegalArgumentException(
      "Unknown resource"
    );
  }
}
