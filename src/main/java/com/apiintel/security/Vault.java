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
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      String encrypted =
        Base64.getEncoder().encodeToString(iv) +
        ":" +
        Base64.getEncoder().encodeToString(
          c.doFinal(value.getBytes(StandardCharsets.UTF_8))
        );
      db.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) connection -> {
        String sql = connection.getMetaData().getURL().startsWith("jdbc:sqlite:")
          ? "INSERT INTO secrets(name,ciphertext) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET ciphertext=excluded.ciphertext"
          : "MERGE INTO secrets KEY(name) VALUES(?,?)";
        try (var statement = connection.prepareStatement(sql)) {
          statement.setString(1, name);
          statement.setString(2, encrypted);
          return statement.executeUpdate();
        }
      });
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encrypt secret");
    }
  }

  public String get(String name) {
    var rows = db.queryForList(
      "SELECT ciphertext FROM secrets WHERE name=?",
      String.class,
      name
    );
    if (rows.isEmpty()) return "";
    try {
      var parts = rows.get(0).split(":");
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))
      );
      c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
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

  public List<String> names() {
    return db.queryForList("SELECT name FROM secrets", String.class);
  }

  public void delete(String name) {
    db.update("DELETE FROM secrets WHERE name=?", name);
  }
}
