package com.apiintel.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Vault {

  private final JdbcTemplate db;
  private final String key;

  public Vault(JdbcTemplate db, @Value("${platform.vault-key}") String key) {
    this.db = db;
    this.key = key;
  }

  private SecretKeySpec key() {
    try {
      byte[] bytes = Base64.getDecoder().decode(key);
      if (bytes.length != 32) throw new IllegalArgumentException();
      return new SecretKeySpec(bytes, "AES");
    } catch (Exception e) {
      throw new IllegalArgumentException(
        "VAULT_KEY must be a base64-encoded 32-byte key"
      );
    }
  }

  public void put(String name, String value) {
    if (
      !name.matches("[a-zA-Z0-9_-]{1,60}")
    ) throw new IllegalArgumentException("Invalid secret name");
    write("secrets", name, encrypt(name, value), name, value);
  }

  public String get(String name) {
    var rows = db.queryForList(
      "SELECT ciphertext FROM secrets WHERE name=?",
      String.class,
      name
    );
    if (rows.isEmpty()) return "";
    return decrypt(name, rows.get(0));
  }

  public List<String> names() {
    return db.queryForList("SELECT name FROM secrets", String.class);
  }

  public void delete(String name) {
    db.update("DELETE FROM secrets WHERE name=?", name);
  }

  public void putScoped(String ownerId, String connectionId, String name, String value) {
    validateScope(ownerId, connectionId, name);
    String aad = aad(ownerId, connectionId, name);
    String ciphertext = encrypt(aad, value);
    db.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) connection -> {
      String sql = connection.getMetaData().getURL().startsWith("jdbc:sqlite:")
        ? "INSERT INTO scoped_secrets(owner_id,connection_id,name,ciphertext) VALUES(?,?,?,?) ON CONFLICT(owner_id,connection_id,name) DO UPDATE SET ciphertext=excluded.ciphertext"
        : "MERGE INTO scoped_secrets KEY(owner_id,connection_id,name) VALUES(?,?,?,?)";
      try (var statement = connection.prepareStatement(sql)) {
        statement.setString(1, ownerId);
        statement.setString(2, connectionId);
        statement.setString(3, name);
        statement.setString(4, ciphertext);
        return statement.executeUpdate();
      }
    });
  }

  public String getScoped(String ownerId, String connectionId, String name) {
    validateScope(ownerId, connectionId, name);
    var rows = db.queryForList(
      "SELECT ciphertext FROM scoped_secrets WHERE owner_id=? AND connection_id=? AND name=?",
      String.class,
      ownerId,
      connectionId,
      name
    );
    if (rows.isEmpty()) return "";
    return decrypt(aad(ownerId, connectionId, name), rows.get(0));
  }

  public void deleteScoped(String ownerId, String connectionId, String name) {
    validateScope(ownerId, connectionId, name);
    db.update(
      "DELETE FROM scoped_secrets WHERE owner_id=? AND connection_id=? AND name=?",
      ownerId,
      connectionId,
      name
    );
  }

  public void deleteConnection(String ownerId, String connectionId) {
    if (ownerId == null || connectionId == null || ownerId.isBlank() || connectionId.isBlank()) {
      throw new IllegalArgumentException("Invalid scoped secret identity");
    }
    db.update(
      "DELETE FROM scoped_secrets WHERE owner_id=? AND connection_id=?",
      ownerId,
      connectionId
    );
  }

  public List<String> scopedNames(String ownerId, String connectionId) {
    return db.queryForList(
      "SELECT name FROM scoped_secrets WHERE owner_id=? AND connection_id=? ORDER BY name",
      String.class,
      ownerId,
      connectionId
    );
  }

  private void write(String table, String name, String ciphertext, String ignoredAad, String ignoredValue) {
    try {
      db.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) connection -> {
        String sql = connection.getMetaData().getURL().startsWith("jdbc:sqlite:")
          ? "INSERT INTO secrets(name,ciphertext) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET ciphertext=excluded.ciphertext"
          : "MERGE INTO secrets KEY(name) VALUES(?,?)";
        try (var statement = connection.prepareStatement(sql)) {
          statement.setString(1, name);
          statement.setString(2, ciphertext);
          return statement.executeUpdate();
        }
      });
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encrypt secret");
    }
  }

  private String encrypt(String aad, String value) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(iv) +
        ":" +
        Base64.getEncoder().encodeToString(
          c.doFinal(value.getBytes(StandardCharsets.UTF_8))
        );
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encrypt secret");
    }
  }

  private String decrypt(String aad, String encrypted) {
    try {
      var parts = encrypted.split(":");
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))
      );
      c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return new String(
        c.doFinal(Base64.getDecoder().decode(parts[1])),
        StandardCharsets.UTF_8
      );
    } catch (Exception e) {
      throw new IllegalArgumentException(
        "Cannot decrypt secret; verify VAULT_KEY"
      );
    }
  }

  private String aad(String ownerId, String connectionId, String name) {
    return ownerId + "|" + connectionId + "|" + name;
  }

  private void validateScope(String ownerId, String connectionId, String name) {
    if (ownerId == null || ownerId.isBlank() || connectionId == null || connectionId.isBlank()) {
      throw new IllegalArgumentException("Invalid scoped secret identity");
    }
    if (name == null || !name.matches("[a-zA-Z0-9_-]{1,60}")) {
      throw new IllegalArgumentException("Invalid secret name");
    }
  }
}
