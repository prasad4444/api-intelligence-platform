package com.apiintel.security;

import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

  private final PlatformUserService users;

  public UserController(PlatformUserService users) {
    this.users = users;
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public List<Map<String, Object>> list() {
    return users.list();
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> create(@RequestBody Map<String, Object> body, Principal principal) {
    @SuppressWarnings("unchecked")
    List<String> roles = body.get("roles") instanceof List<?> list
      ? list.stream().map(String::valueOf).toList()
      : List.of();
    var created = users.create(
      String.valueOf(body.getOrDefault("username", "")),
      String.valueOf(body.getOrDefault("password", "")),
      String.valueOf(body.getOrDefault("displayName", body.getOrDefault("username", ""))),
      roles,
      !Boolean.FALSE.equals(body.get("enabled"))
    );
    return created;
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
    @SuppressWarnings("unchecked")
    List<String> roles = body.get("roles") instanceof List<?> list
      ? list.stream().map(String::valueOf).toList()
      : null;
    Boolean enabled = body.containsKey("enabled") ? Boolean.valueOf(String.valueOf(body.get("enabled"))) : null;
    String password = body.containsKey("password") ? String.valueOf(body.get("password")) : null;
    String display = body.containsKey("displayName") ? String.valueOf(body.get("displayName")) : null;
    return users.update(id, display, roles, enabled, password);
  }
}
