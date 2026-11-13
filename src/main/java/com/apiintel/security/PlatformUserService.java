package com.apiintel.security;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformUserService implements UserDetailsService {

  private final JdbcTemplate db;
  private final PasswordEncoder encoder;

  public PlatformUserService(JdbcTemplate db, PasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  @Transactional
  public void bootstrap(String admin, String developer, String reviewer) {
    if (admin == null || admin.isBlank()) {
      throw new IllegalStateException("Set ADMIN_PASSWORD before starting the platform");
    }
    ensure("admin", admin, "Administrator", List.of("ADMIN", "REVIEWER", "DEVELOPER", "TESTER"));
    if (developer != null && !developer.isBlank()) {
      ensure("developer", developer, "Developer", List.of("DEVELOPER", "TESTER"));
    }
    if (reviewer != null && !reviewer.isBlank()) {
      ensure("reviewer", reviewer, "Reviewer", List.of("REVIEWER"));
    }
  }

  private void ensure(String username, String password, String display, List<String> roles) {
    var existing = findByUsername(username);
    if (existing.isPresent()) {
      var row = existing.get();
      db.update(
        "UPDATE platform_users SET password_hash=?, display_name=?, roles=?, enabled=1, updated_at=? WHERE id=?",
        encoder.encode(password),
        display,
        String.join(",", roles),
        Instant.now().toString(),
        row.get("id")
      );
      return;
    }
    create(username, password, display, roles, true);
  }

  public Map<String, Object> create(
    String username,
    String password,
    String displayName,
    List<String> roles,
    boolean enabled
  ) {
    if (username == null || !username.matches("[a-zA-Z0-9._-]{2,60}")) {
      throw new IllegalArgumentException("Username must be 2–60 letters, digits, dots, underscores or hyphens");
    }
    if (password == null || password.length() < 10) {
      throw new IllegalArgumentException("Password must be at least 10 characters");
    }
    if (roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("Assign at least one role");
    }
    for (String role : roles) {
      if (!Set.of("ADMIN", "REVIEWER", "DEVELOPER", "TESTER").contains(role)) {
        throw new IllegalArgumentException("Unsupported role: " + role);
      }
    }
    if (findByUsername(username).isPresent()) {
      throw new IllegalArgumentException("Username already exists");
    }
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    db.update(
      "INSERT INTO platform_users(id,username,password_hash,display_name,roles,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
      id,
      username,
      encoder.encode(password),
      displayName == null ? username : displayName,
      String.join(",", roles),
      enabled ? 1 : 0,
      now.toString(),
      now.toString()
    );
    return publicView(require(id));
  }

  public Map<String, Object> update(
    String id,
    String displayName,
    List<String> roles,
    Boolean enabled,
    String password
  ) {
    var row = require(id);
    if (displayName != null) row.put("display_name", displayName);
    if (roles != null && !roles.isEmpty()) {
      for (String role : roles) {
        if (!Set.of("ADMIN", "REVIEWER", "DEVELOPER", "TESTER").contains(role)) {
          throw new IllegalArgumentException("Unsupported role: " + role);
        }
      }
      row.put("roles", String.join(",", roles));
    }
    if (enabled != null) row.put("enabled", enabled ? 1 : 0);
    String hash = (String) row.get("password_hash");
    if (password != null && !password.isBlank()) {
      if (password.length() < 10) throw new IllegalArgumentException("Password must be at least 10 characters");
      hash = encoder.encode(password);
    }
    db.update(
      "UPDATE platform_users SET display_name=?, roles=?, enabled=?, password_hash=?, updated_at=? WHERE id=?",
      row.get("display_name"),
      row.get("roles"),
      row.get("enabled"),
      hash,
      Instant.now().toString(),
      id
    );
    return publicView(require(id));
  }

  public List<Map<String, Object>> list() {
    return db
      .queryForList(
        "SELECT id,username,display_name,roles,enabled,created_at,updated_at FROM platform_users ORDER BY username"
      )
      .stream()
      .map(this::normalize)
      .toList();
  }

  public Optional<Map<String, Object>> findByUsername(String username) {
    var rows = db.queryForList("SELECT * FROM platform_users WHERE username=?", username);
    return rows.stream().findFirst().map(this::normalize);
  }

  public Map<String, Object> require(String id) {
    var rows = db.queryForList("SELECT * FROM platform_users WHERE id=?", id);
    if (rows.isEmpty()) throw new IllegalArgumentException("User not found");
    return normalize(rows.get(0));
  }

  public Map<String, Object> requireUsername(String username) {
    return findByUsername(username).orElseThrow(() -> new IllegalArgumentException("User not found"));
  }

  public Map<String, Object> publicView(Map<String, Object> row) {
    var view = new LinkedHashMap<String, Object>();
    view.put("id", row.get("id"));
    view.put("username", row.get("username"));
    view.put("displayName", row.get("display_name"));
    view.put("roles", Arrays.asList(String.valueOf(row.get("roles")).split(",")));
    view.put("enabled", toBool(row.get("enabled")));
    view.put("createdAt", row.get("created_at"));
    view.put("updatedAt", row.get("updated_at"));
    return view;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    var row = findByUsername(username).orElseThrow(() -> new UsernameNotFoundException(username));
    if (!toBool(row.get("enabled"))) throw new UsernameNotFoundException(username);
    var authorities = Arrays.stream(String.valueOf(row.get("roles")).split(","))
      .filter(r -> !r.isBlank())
      .map(r -> new SimpleGrantedAuthority("ROLE_" + r.trim()))
      .collect(Collectors.toList());
    return User.withUsername(String.valueOf(row.get("username")))
      .password(String.valueOf(row.get("password_hash")))
      .authorities(authorities)
      .build();
  }

  private Map<String, Object> normalize(Map<String, Object> row) {
    var copy = new LinkedHashMap<>(row);
    if (copy.containsKey("DISPLAY_NAME")) copy.putIfAbsent("display_name", copy.get("DISPLAY_NAME"));
    if (copy.containsKey("PASSWORD_HASH")) copy.putIfAbsent("password_hash", copy.get("PASSWORD_HASH"));
    if (copy.containsKey("CREATED_AT")) copy.putIfAbsent("created_at", copy.get("CREATED_AT"));
    if (copy.containsKey("UPDATED_AT")) copy.putIfAbsent("updated_at", copy.get("UPDATED_AT"));
    if (copy.containsKey("USERNAME")) copy.putIfAbsent("username", copy.get("USERNAME"));
    if (copy.containsKey("ROLES")) copy.putIfAbsent("roles", copy.get("ROLES"));
    if (copy.containsKey("ENABLED")) copy.putIfAbsent("enabled", copy.get("ENABLED"));
    if (copy.containsKey("ID")) copy.putIfAbsent("id", copy.get("ID"));
    return copy;
  }

  private boolean toBool(Object value) {
    if (value instanceof Boolean b) return b;
    if (value instanceof Number n) return n.intValue() != 0;
    return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
  }
}
