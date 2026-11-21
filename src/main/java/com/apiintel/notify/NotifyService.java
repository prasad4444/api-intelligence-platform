package com.apiintel.notify;

import com.apiintel.integrations.ApprovedHosts;
import com.apiintel.security.PlatformUserService;
import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.apiintel.store.Rows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transactional notification outbox: messages are inserted with a unique
 * dedup_key in the same transaction as the state change that caused them, then
 * a dispatcher tick sends with bounded retries + backoff, ending in DEAD.
 * Webhooks carry an Idempotency-Key header; webhook URLs live in the vault and
 * are never returned to clients.
 */
@Service
public class NotifyService {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;
  private static final int MAX_ATTEMPTS = 5;

  private final JdbcTemplate db;
  private final RecordStore store;
  private final Vault vault;
  private final PlatformUserService users;
  private final ApprovedHosts approved;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final JavaMailSender mail; // null unless spring.mail.host configured
  private final Set<String> allowedEmailDomains;

  public NotifyService(JdbcTemplate db, RecordStore store, Vault vault,
                       PlatformUserService users, ApprovedHosts approved,
                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                       JavaMailSender mail,
                       @Value("${platform.notify.allowed-email-domains:}") String allowedDomains) {
    this.db = db;
    this.store = store;
    this.vault = vault;
    this.users = users;
    this.approved = approved;
    this.mail = mail;
    this.allowedEmailDomains = Set.of(allowedDomains.split(","));
  }

  private String ownerId(String username) {
    return String.valueOf(users.requireUsername(username).get("id"));
  }

  // ---------- outbox ----------

  /** Insert one outbox row; dedup_key UNIQUE makes repeat calls a no-op. */
  public boolean enqueue(String ownerId, String dedupKey, String destinationId, ObjectNode payload) {
    String id = UUID.randomUUID().toString();
    String idem = UUID.randomUUID().toString();
    int rows = db.update("INSERT INTO notification_outbox(id,owner_id,dedup_key,destination_id,payload,"
        + "status,attempts,next_attempt_at,idempotency_key,created_at)"
        + " SELECT ?,?,?,?,?,'PENDING',0,?,?,? WHERE NOT EXISTS"
        + " (SELECT 1 FROM notification_outbox WHERE dedup_key=?)",
        id, ownerId, dedupKey, destinationId, payload.toString(),
        Instant.now().toString(), idem, Instant.now().toString(), dedupKey);
    return rows == 1;
  }

  /** Once-only in-app notification (used by scheduler credential failures). */
  public void notifyOnce(String ownerId, String dedupKey, String title, String body) {
    if (enqueue(ownerId, dedupKey, null, J.objectNode()
        .put("type", "IN_APP").put("title", title).put("body", body))) {
      db.update("INSERT INTO in_app_notifications(id,owner_id,type,title,body,is_read,created_at)"
          + " VALUES(?,?,?,?,?,0,?)",
          UUID.randomUUID().toString(), ownerId, "ALERT", title, body, Instant.now().toString());
      // mark the outbox row as already-delivered (in-app was written inline)
      db.update("UPDATE notification_outbox SET status='SENT',sent_at=? WHERE dedup_key=?",
          Instant.now().toString(), dedupKey);
    }
  }

  /** Dispatcher pass — send due outbox rows; retries with backoff; then DEAD. */
  @Scheduled(fixedDelayString = "5000", initialDelayString = "7000")
  public int dispatch() {
    int sent = 0;
    for (Map<String, Object> row : dueRows()) {
      String id = Rows.str(row, "id");
      try {
        deliver(row);
        db.update("UPDATE notification_outbox SET status='SENT',sent_at=? WHERE id=?",
            Instant.now().toString(), id);
        sent++;
      } catch (RuntimeException e) {
        int attempts = Rows.num(row, "attempts") + 1;
        if (attempts >= MAX_ATTEMPTS) {
          db.update("UPDATE notification_outbox SET status='DEAD',attempts=?,last_error=? WHERE id=?",
              attempts, abbrev(e.getMessage()), id);
        } else {
          long backoff = 5L * (1L << Math.min(attempts, 6));
          db.update("UPDATE notification_outbox SET attempts=?,last_error=?,next_attempt_at=? WHERE id=?",
              attempts, abbrev(e.getMessage()), Instant.now().plusSeconds(backoff).toString(), id);
        }
      }
    }
    return sent;
  }

  private List<Map<String, Object>> dueRows() {
    return db.queryForList("SELECT * FROM notification_outbox WHERE status IN ('PENDING','FAILED')"
        + " AND (next_attempt_at IS NULL OR next_attempt_at <= ?) LIMIT 20",
        Instant.now().toString());
  }

  private void deliver(Map<String, Object> row) {
    try {
      ObjectNode payload = (ObjectNode) json.readTree(Rows.str(row, "payload"));
      String destId = Rows.str(row, "destination_id");
      if (destId == null) {
        // in-app only
        db.update("INSERT INTO in_app_notifications(id,owner_id,type,title,body,is_read,created_at)"
            + " VALUES(?,?,?,?,?,0,?)",
            UUID.randomUUID().toString(), Rows.str(row, "owner_id"),
            payload.path("type").asText("ALERT"), payload.path("title").asText(""),
            payload.path("body").asText(""), Instant.now().toString());
        return;
      }
      ObjectNode dest = store.get("notify-destinations", destId);
      if (!dest.path("enabled").asBoolean(false)) {
        throw new IllegalStateException("Destination disabled");
      }
      switch (dest.path("type").asText()) {
        case "IN_APP" -> db.update(
            "INSERT INTO in_app_notifications(id,owner_id,type,title,body,is_read,created_at) VALUES(?,?,?,?,?,0,?)",
            UUID.randomUUID().toString(), Rows.str(row, "owner_id"), payload.path("type").asText("ALERT"),
            payload.path("title").asText(""), payload.path("body").asText(""), Instant.now().toString());
        case "WEBHOOK", "SLACK", "TEAMS" -> webhook(dest, row, payload);
        case "EMAIL" -> email(dest, payload);
        default -> throw new IllegalStateException("Unsupported destination type");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e.getMessage() == null ? "deliver failed" : e.getMessage());
    }
  }

  private void webhook(ObjectNode dest, Map<String, Object> row, ObjectNode payload) throws Exception {
    String url = vault.getScoped(dest.path("ownerId").asText(), dest.path("id").asText(), "url");
    if (url.isBlank()) throw new IllegalStateException("Webhook URL missing");
    approved.enforce(url, null);
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", Rows.str(row, "idempotency_key"))
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        .build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() >= 500) throw new IllegalStateException("webhook HTTP " + res.statusCode());
    if (res.statusCode() >= 400) {
      throw new IllegalStateException("webhook rejected: HTTP " + res.statusCode());
    }
  }

  private void email(ObjectNode dest, ObjectNode payload) {
    if (mail == null) {
      throw new IllegalStateException("EMAIL destinations require spring.mail.host configuration");
    }
    var msg = mail.createMimeMessage();
    try {
      var helper = new org.springframework.mail.javamail.MimeMessageHelper(msg, false);
      ArrayNode recips = (ArrayNode) dest.path("recipients");
      for (JsonNode r : recips) helper.addTo(r.asText());
      helper.setSubject(payload.path("title").asText("Platform notification"));
      helper.setText(payload.path("body").asText(""));
      mail.send(msg);
    } catch (Exception e) {
      throw new IllegalStateException("email send failed: " + e.getMessage());
    }
  }

  // ---------- destinations ----------

  public List<ObjectNode> destinations(String username) {
    String ownerId = ownerId(username);
    return store.list("notify-destinations").stream()
        .filter(d -> ownerId.equals(d.path("ownerId").asText()))
        .map(this::redactDest).toList();
  }

  public ObjectNode saveDestination(ObjectNode input, String username) {
    String ownerId = ownerId(username);
    ObjectNode dest = input.hasNonNull("id")
        ? store.get("notify-destinations", input.path("id").asText())
        : J.objectNode();
    if (dest.has("ownerId") && !ownerId.equals(dest.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Destination belongs to another user");
    }
    dest.put("ownerId", ownerId);
    String type = input.path("type").asText(dest.path("type").asText("IN_APP"));
    if (!Set.of("IN_APP", "EMAIL", "SLACK", "TEAMS", "WEBHOOK").contains(type)) {
      throw new IllegalArgumentException("Unsupported destination type");
    }
    dest.put("type", type);
    if (input.hasNonNull("name")) dest.put("name", input.path("name").asText());
    if (input.has("recipients")) dest.set("recipients", input.get("recipients"));
    if ("EMAIL".equals(type)) validateRecipients((ArrayNode) dest.path("recipients"), username);
    if (!dest.has("enabled")) dest.put("enabled", false);
    dest = store.save("notify-destinations", dest, username);
    String url = input.path("url").asText("");
    if (!url.isBlank()) {
      // URL is stored only; its host is approved at enable time (explicit opt-in)
      URI u = URI.create(url);
      if (!Set.of("http", "https").contains(u.getScheme()) || u.getHost() == null
          || u.getUserInfo() != null) {
        throw new IllegalArgumentException("Use an HTTP(S) URL without embedded credentials");
      }
      vault.putScoped(ownerId, dest.path("id").asText(), "url", url);
      dest.put("hasUrl", true);
      dest = store.save("notify-destinations", dest, username);
    }
    return redactDest(dest);
  }

  private void validateRecipients(ArrayNode recipients, String username) {
    for (JsonNode r : recipients) {
      String email = r.asText();
      String domain = email.contains("@") ? email.substring(email.indexOf('@') + 1) : "";
      boolean platformUser = users.findByUsername(email.split("@")[0]).isPresent();
      if (!platformUser && !allowedEmailDomains.contains(domain)) {
        throw new IllegalArgumentException("Recipient " + domain + " is not a platform user or allowed domain");
      }
    }
  }

  public ObjectNode previewDestination(String id, String username) {
    var dest = store.get("notify-destinations", id);
    if (!ownerId(username).equals(dest.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Destination belongs to another user");
    }
    ObjectNode payload = J.objectNode();
    payload.put("type", "PREVIEW");
    payload.put("title", "Test notification");
    payload.put("body", "This is the payload that would be delivered.");
    ObjectNode out = J.objectNode();
    out.set("payload", payload);
    out.put("type", dest.path("type").asText());
    out.put("recipients", dest.path("recipients"));
    out.put("enabled", dest.path("enabled").asBoolean());
    return out;
  }

  public ObjectNode enableDestination(String id, ObjectNode input, String username) {
    var dest = store.get("notify-destinations", id);
    if (!ownerId(username).equals(dest.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Destination belongs to another user");
    }
    if (!input.path("confirm").asBoolean(false)) {
      throw new IllegalArgumentException("Pass confirm:true after reviewing the preview");
    }
    if ("EMAIL".equals(dest.path("type").asText()) && mail == null) {
      throw new IllegalStateException("EMAIL destinations require spring.mail.host configuration");
    }
    String url = vault.getScoped(dest.path("ownerId").asText(), dest.path("id").asText(), "url");
    if (!url.isBlank()) {
      // explicit opt-in: approve the destination host now that preview +
      // recipient authorization checks passed; enforce runs before every send
      approved.approve(url);
      approved.enforce(url, null);
    }
    dest.put("enabled", true);
    dest.put("verifiedAt", Instant.now().toString());
    return redactDest(store.save("notify-destinations", dest, username));
  }

  public ObjectNode disableDestination(String id, String username) {
    var dest = store.get("notify-destinations", id);
    if (!ownerId(username).equals(dest.path("ownerId").asText())) {
      throw new org.springframework.security.access.AccessDeniedException("Destination belongs to another user");
    }
    dest.put("enabled", false);
    return redactDest(store.save("notify-destinations", dest, username));
  }

  private ObjectNode redactDest(ObjectNode d) {
    var v = d.deepCopy();
    v.remove("url");
    v.put("hasUrl", !vault.getScoped(d.path("ownerId").asText(), d.path("id").asText(), "url").isBlank());
    return v;
  }

  // ---------- in-app notifications ----------

  public List<Map<String, Object>> notifications(String username) {
    return db.queryForList("SELECT * FROM in_app_notifications WHERE owner_id=? ORDER BY created_at DESC LIMIT 100",
        ownerId(username));
  }

  public void markRead(String id, String username) {
    if (db.update("UPDATE in_app_notifications SET is_read=1 WHERE id=? AND owner_id=?",
        id, ownerId(username)) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
    }
  }

  public List<Map<String, Object>> outboxHistory(String username) {
    return db.queryForList("SELECT id,dedup_key,destination_id,status,attempts,last_error,created_at,sent_at"
        + " FROM notification_outbox WHERE owner_id=? ORDER BY created_at DESC LIMIT 100",
        ownerId(username));
  }

  private String abbrev(String s) {
    return s == null ? "" : s.substring(0, Math.min(300, s.length()));
  }
}
