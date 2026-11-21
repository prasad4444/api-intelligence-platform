package com.apiintel.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Short-lived preview→confirm grants shared by /api/ai-tools and /api/chat.
 * The fingerprint must cover the exact outbound request (prompt, context,
 * destination, and — for chat — any transferred history).
 */
@Component
public class ConfirmationGrants {

  private record Grant(String actor, String fingerprint, Instant expires) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  public String issue(String actor, String fingerprint) {
    Instant now = Instant.now();
    grants.values().removeIf(grant -> !grant.expires().isAfter(now));
    if (grants.size() >= 500) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many pending confirmations");
    }
    String ticket = UUID.randomUUID().toString();
    grants.put(ticket, new Grant(actor, fingerprint, now.plusSeconds(180)));
    return ticket;
  }

  public void consume(String confirmationId, String actor, String fingerprint) {
    Grant grant = grants.get(confirmationId);
    if (grant == null || !grant.expires().isAfter(Instant.now())
        || !grant.actor().equals(actor) || !grant.fingerprint().equals(fingerprint)
        || !grants.remove(confirmationId, grant)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview and confirm this exact request again");
    }
  }

  public static String fingerprint(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
