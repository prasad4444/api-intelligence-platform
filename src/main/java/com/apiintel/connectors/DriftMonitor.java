package com.apiintel.connectors;

import com.apiintel.activity.ActivityLog;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Tracks per-connector execution outcomes and detects response schema drift.
 * On each successful connector call the response's structural shape is hashed
 * and compared with the {@code schemaHash} stored when the connector version
 * was saved; a mismatch flags {@code driftDetected}, emits a SCHEMA_DRIFT
 * timeline event and degrades the connector's health status.
 */
@Service
public class DriftMonitor {

  private final RecordStore store;
  private final ActivityLog activity;

  public DriftMonitor(RecordStore store, ActivityLog activity) {
    this.store = store;
    this.activity = activity;
  }

  /** Structural hash: types + key names only — values never enter the digest. */
  public static String shapeHash(JsonNode node) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(shape(node).getBytes(StandardCharsets.UTF_8));
      var hex = new StringBuilder();
      for (byte b : digest.digest()) hex.append(String.format("%02x", b));
      return hex.substring(0, 16);
    } catch (Exception e) {
      return "";
    }
  }

  private static String shape(JsonNode node) {
    if (node == null) return "n";
    if (node.isObject()) {
      List<String> keys = new ArrayList<>();
      node.fieldNames().forEachRemaining(keys::add);
      Collections.sort(keys);
      var sb = new StringBuilder("{");
      for (String k : keys) sb.append(k).append(':').append(shape(node.get(k))).append(',');
      return sb.append('}').toString();
    }
    if (node.isArray()) {
      return "[" + (node.isEmpty() ? "" : shape(node.get(0))) + "]";
    }
    if (node.isNumber()) return "num";
    if (node.isBoolean()) return "bool";
    if (node.isTextual()) return "str";
    return node.getNodeType().name().toLowerCase();
  }

  /** Successful connector call → counters + optional drift check. */
  public void onSuccess(ObjectNode config, JsonNode response) {
    mutate(config, c -> {
      c.put("executions", c.path("executions").asLong(0) + 1);
      c.put("lastSuccessAt", Instant.now().toString());
      String stored = c.path("schemaHash").asText("");
      if (!stored.isBlank()) {
        String actual = shapeHash(response);
        if (!actual.isBlank() && !stored.equals(actual)) {
          c.put("driftDetected", true);
          c.put("driftAt", Instant.now().toString());
          c.put("health", "DEGRADED");
          activity.emit(c.path("ownerId").asText(), c.path("id").asText(), null,
              "SCHEMA_DRIFT", "Response schema changed (expected " + stored + ", got " + actual + ")");
        }
      }
    });
  }

  /** Failed connector call → failure counter. */
  public void onFailure(ObjectNode config) {
    mutate(config, c -> c.put("failures", c.path("failures").asLong(0) + 1));
  }

  private void mutate(ObjectNode config, java.util.function.Consumer<ObjectNode> fn) {
    String id = config.path("id").asText("");
    if (id.isBlank() || !config.path("ownerId").isTextual()) return;
    // Counter updates race with other writers of the same connection record
    // (e.g. verify state changes during connect) — retry once on revision
    // conflict, then drop the update rather than break the caller.
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        var record = store.get("connections", id);
        fn.accept(record);
        store.save("connections", record, record.path("owner").asText("system"));
        return;
      } catch (RuntimeException e) {
        if (attempt == 1 || e.getMessage() == null
            || !e.getMessage().contains("Record changed")) return;
      }
    }
  }
}
