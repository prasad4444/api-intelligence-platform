package com.apiintel;

import static org.junit.jupiter.api.Assertions.*;

import com.apiintel.knowledge.KnowledgeService;
import com.apiintel.security.PlatformUserService;
import com.apiintel.schedule.ScheduleService;
import com.apiintel.schedule.SchedulerEngine;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * File-backed SQLite coverage: real Flyway migrations (incl. FTS5), knowledge
 * search with bm25, durable schedule state surviving a "restart" (fresh
 * engine/connection on the same file).
 */
class ObservabilityPersistenceTest {

  static final JsonNodeFactory J = JsonNodeFactory.instance;

  @TempDir java.nio.file.Path dir;

  JdbcTemplate boot(String file) {
    String url = "jdbc:sqlite:" + file;
    Flyway.configure().dataSource(url, "", "").locations("classpath:db/sqlite").load().migrate();
    return new JdbcTemplate(new DriverManagerDataSource(url, "", ""));
  }

  @Test
  void fts5SearchAndRestartRecovery() {
    String file = dir.resolve("platform.sqlite").toString();
    var db = boot(file);
    var users = new PlatformUserService(db, new BCryptPasswordEncoder());
    users.bootstrap("admin-password-1", "developer-password-1", "reviewer-password-1");
    var alice = users.create("alice", "alice-password-1", "Alice", List.of("DEVELOPER"), true);
    String aliceId = String.valueOf(alice.get("id"));

    var knowledge = new KnowledgeService(db, users, 7, 90);
    assertTrue(knowledge.ftsEnabled(), "SQLite should provide FTS5");
    knowledge.indexChatMessage(aliceId, "conv-1", "m1", "assistant",
        "the NullPointerException in checkout was fixed by revert");
    knowledge.index(aliceId, "INCIDENT_RESOLUTION", "Checkout NPE",
        "revert deploy 41 resolved the outage", J.arrayNode(), null);
    var hits = knowledge.search("alice", "checkout", List.of());
    assertEquals(2, hits.size());
    var chatOnly = knowledge.search("alice", "revert", List.of("CHAT_MESSAGE"));
    assertEquals(1, chatOnly.size());
    // bm25 rank present on FTS path
    assertNotNull(hits.get(0).path("rank"));

    // seed a durable schedule + queued run, then "restart": fresh objects, same file
    new com.apiintel.store.RecordStore(db, new com.fasterxml.jackson.databind.ObjectMapper());
    String schedId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    db.update("INSERT INTO schedules(id,owner_id,report_id,kind,expr,zone,status,next_run_at,created_at,updated_at)"
        + " VALUES(?,?,'r1','INTERVAL','60','UTC','ACTIVE',?,?,?)",
        schedId, aliceId, Instant.now().plusSeconds(3600).toString(),
        Instant.now().toString(), Instant.now().toString());
    db.update("INSERT INTO schedule_runs(id,schedule_id,owner_id,execution_key,planned_at,status)"
        + " VALUES(?,?,?,?,?,'QUEUED')",
        runId, schedId, aliceId, schedId + "|1", Instant.now().toString());

    // restart: new JdbcTemplate/engine on the same file must see the queued run
    var db2 = boot(file);
    var rows = db2.queryForList("SELECT * FROM schedule_runs WHERE id=?", runId);
    assertEquals(1, rows.size());
    var users2 = new PlatformUserService(db2, new BCryptPasswordEncoder());
    var store2 = new com.apiintel.store.RecordStore(db2, new com.fasterxml.jackson.databind.ObjectMapper());
    var schedules2 = new ScheduleService(db2, users2, null);
    var run = schedules2.runById(runId);
    assertEquals("QUEUED", run.path("status").asText());
  }
}
