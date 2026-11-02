package com.apiintel.capacity;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jira")
public class StoryCsvController {

  private final RecordStore store;

  public StoryCsvController(RecordStore s) {
    store = s;
  }

  @PostMapping("/csv")
  @Transactional
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object load(@RequestBody Map<String, String> input, Principal p) {
    String text = input.getOrDefault("csv", "");
    if (text.length() > 2_000_000) throw new IllegalArgumentException(
      "CSV too large"
    );
    var rows = parse(text);
    if (rows.isEmpty()) throw new IllegalArgumentException(
      "CSV header is required"
    );
    var header = rows.remove(0);
    if (
      !header.containsAll(List.of("Key", "Summary", "Status"))
    ) throw new IllegalArgumentException(
      "CSV needs Key, Summary and Status headers"
    );
    var errors = new ArrayList<Map<String, Object>>();
    var pending = new ArrayList<ObjectNode>();
    var seen = new HashSet<String>();
    for (int i = 0; i < rows.size(); i++) {
      var values = rows.get(i);
      if (values.stream().allMatch(String::isBlank)) continue;
      try {
        if (values.size() != header.size()) throw new IllegalArgumentException(
          "Column count does not match header"
        );
        var row = store.parse("{}");
        for (int c = 0; c < header.size(); c++) {
          String key = switch (header.get(c)) {
            case "Key" -> "key";
            case "Summary" -> "summary";
            case "Status" -> "status";
            case "Priority" -> "priority";
            case "Sprint" -> "sprint";
            case "Developer" -> "developer";
            case "Tester" -> "tester";
            case "Dev Points" -> "devPoints";
            case "Test Points" -> "testPoints";
            default -> "";
          };
          if (!key.isBlank()) {
            if (key.endsWith("Points")) {
              double n = Double.parseDouble(values.get(c));
              if (
                n < 0 || !Double.isFinite(n)
              ) throw new IllegalArgumentException("Invalid points");
              row.put(key, n);
            } else row.put(key, values.get(c));
          }
        }
        if (
          row.path("key").asText().isBlank() ||
          row.path("summary").asText().isBlank()
        ) throw new IllegalArgumentException("Key and Summary cannot be blank");
        if (
          !seen.add(row.path("key").asText())
        ) throw new IllegalArgumentException("Duplicate key");
        String rowKey = row.path("key").asText();
        var old = store
          .list("stories")
          .stream()
          .filter(s -> s.path("key").asText().equals(rowKey))
          .findFirst();
        if (old.isPresent()) {
          var merged = old.get().deepCopy();
          merged.setAll(row);
          row = merged;
        }
        pending.add(row);
      } catch (Exception e) {
        errors.add(Map.of("row", i + 2, "message", e.getMessage()));
      }
    }
    if (!errors.isEmpty()) return Map.of("imported", 0, "errors", errors);
    for (var row : pending) store.save("stories", row, p.getName());
    return Map.of("imported", pending.size(), "errors", errors);
  }

  public List<List<String>> parse(String csv) {
    var rows = new ArrayList<List<String>>();
    var row = new ArrayList<String>();
    var field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < csv.length(); i++) {
      char c = csv.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else quoted = !quoted;
      } else if (c == ',' && !quoted) {
        row.add(field.toString());
        field.setLength(0);
      } else if (c == '\n' && !quoted) {
        row.add(field.toString().replaceAll("\r$", ""));
        rows.add(row);
        row = new ArrayList<>();
        field.setLength(0);
      } else field.append(c);
    }
    if (quoted) throw new IllegalArgumentException("Unclosed CSV quote");
    if (field.length() > 0 || !row.isEmpty()) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }
}
