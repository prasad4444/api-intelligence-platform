package com.apiintel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V1–V3 data must survive the V4 chat/studio migration on file-backed SQLite. */
class MigrationV4Test {

  @Test
  void v4UpgradePreservesExistingData() throws Exception {
    var file = Files.createTempDirectory("v4-migration").resolve("platform.sqlite");
    String url = "jdbc:sqlite:" + file;

    // Upgrade only to V3, seed data, then migrate the rest.
    MigrateResult first = Flyway.configure()
      .dataSource(url, "", "")
      .locations("classpath:db/sqlite")
      .target("3")
      .load()
      .migrate();
    assertTrue(first.success);

    var db = new JdbcTemplate(new DriverManagerDataSource(url, "", ""));
    db.update("INSERT INTO records(id,kind,revision,payload,updated_at) VALUES(?,?,?,?,?)",
      "rec-1", "connections", 1, "{\"id\":\"rec-1\",\"provider\":\"jira\"}", "2024-01-01T00:00:00Z");
    db.update("INSERT INTO secrets(name,ciphertext) VALUES(?,?)", "legacy", "cipher-1");
    db.update("INSERT INTO scoped_secrets(owner_id,connection_id,name,ciphertext) VALUES(?,?,?,?)",
      "owner-1", "conn-1", "credential", "cipher-2");
    db.update("INSERT INTO platform_users(id,username,password_hash,display_name,roles,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
      "u-1", "alice", "hash", "Alice", "DEVELOPER", 1, "2024-01-01", "2024-01-01");

    MigrateResult second = Flyway.configure()
      .dataSource(url, "", "")
      .locations("classpath:db/sqlite")
      .load()
      .migrate();
    assertTrue(second.success);
    assertTrue(second.migrationsExecuted >= 1);

    assertEquals(1, db.queryForObject(
      "SELECT COUNT(*) FROM records WHERE id='rec-1'", Integer.class));
    assertEquals("cipher-1", db.queryForObject(
      "SELECT ciphertext FROM secrets WHERE name='legacy'", String.class));
    assertEquals("cipher-2", db.queryForObject(
      "SELECT ciphertext FROM scoped_secrets WHERE owner_id='owner-1'", String.class));
    assertEquals(1, db.queryForObject(
      "SELECT COUNT(*) FROM platform_users WHERE username='alice'", Integer.class));

    // V4 tables exist and accept owner-scoped rows.
    db.update("INSERT INTO chat_conversations(id,owner_id,connection_id,provider,title,created_at,updated_at,provider_state) VALUES(?,?,?,?,?,?,?,?)",
      "cv-1", "owner-1", "conn-1", "stylus", "t", "2024-01-01", "2024-01-01", "{}");
    db.update("INSERT INTO chat_messages(id,conversation_id,owner_id,seq,role,content,provider,connection_id,account,status,meta,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
      "m-1", "cv-1", "owner-1", 1, "user", "hi", null, null, null, "complete", "{}", "2024-01-01");
    db.update("INSERT INTO activity_events(owner_id,connection_id,studio_session_id,type,message,created_at) VALUES(?,?,?,?,?,?)",
      "owner-1", "conn-1", "s-1", "BROWSER_OPENED", "opened", "2024-01-01");
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE conversation_id='cv-1'", Integer.class));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM activity_events WHERE owner_id='owner-1'", Integer.class));
  }
}
