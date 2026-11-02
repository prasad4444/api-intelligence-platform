package com.apiintel.capacity;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkbookService {

  private final RecordStore store;

  public WorkbookService(RecordStore s) {
    store = s;
  }

  private static final Map<String, String[]> COLUMNS = new LinkedHashMap<>();
  private static final Map<String, String> KINDS = Map.of(
    "Stories",
    "stories",
    "Team",
    "team",
    "Sprints",
    "sprints",
    "Leaves",
    "leaves",
    "Holidays",
    "holidays"
  );

  static {
    COLUMNS.put("Stories", new String[] {
      "Key:key",
      "Summary:summary",
      "Priority:priority",
      "Status:status",
      "Sprint:sprint",
      "Dev Points:devPoints",
      "Test Points:testPoints",
      "Developer:developer",
      "Tester:tester",
      "Epic:epic",
      "Fix Version:fixVersion",
    });
    COLUMNS.put("Team", new String[] {
      "Name:name",
      "Role:role",
      "Region:region",
      "Hours/Day:hoursPerDay",
      "Allocation %:allocation",
    });
    COLUMNS.put("Sprints", new String[] {
      "Name:name",
      "Start:start",
      "End:end",
    });
    COLUMNS.put("Leaves", new String[] {
      "Name:name",
      "From:from",
      "To:to",
      "Type:type",
    });
    COLUMNS.put("Holidays", new String[] {
      "Date:date",
      "Region:region",
      "Name:name",
    });
  }

  public byte[] export(boolean template) {
    try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var style = wb.createCellStyle();
      var font = wb.createFont();
      font.setBold(true);
      font.setColor(IndexedColors.WHITE.getIndex());
      style.setFont(font);
      style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      for (var entry : COLUMNS.entrySet()) {
        var sheet = wb.createSheet(entry.getKey());
        var headers = sheet.createRow(0);
        var cols = entry.getValue();
        for (int c = 0; c < cols.length; c++) {
          var cell = headers.createCell(c);
          cell.setCellValue(cols[c].split(":")[0]);
          cell.setCellStyle(style);
          sheet.setColumnWidth(c, 6000);
          String key = cols[c].split(":")[1];
          String[] options = switch (key) {
            case "priority" -> new String[] { "High", "Medium", "Low" };
            case "role" -> new String[] { "Dev", "Test" };
            case "region" -> new String[] { "USA", "India" };
            default -> null;
          };
          if (options != null) {
            var helper = sheet.getDataValidationHelper();
            sheet.addValidationData(
              helper.createValidation(
                helper.createExplicitListConstraint(options),
                new CellRangeAddressList(1, 5000, c, c)
              )
            );
          }
        }
        sheet.createFreezePane(0, 1);
        if (!template) {
          int row = 1;
          for (var record : store.list(KINDS.get(entry.getKey()))) {
            var rr = sheet.createRow(row++);
            for (int c = 0; c < cols.length; c++) rr.createCell(c).setCellValue(
              record.path(cols[c].split(":")[1]).asText()
            );
          }
        }
      }
      var instructions = wb.createSheet("Instructions");
      String[] text = {
        "Use ISO dates: YYYY-MM-DD. Do not rename headers.",
        "Stories upsert by Key. Team and Sprints upsert by Name.",
        "Leaves upsert by Name + From + To. Holidays by Date + Region + Name.",
        "Roles: Dev / Test. Regions: USA / India. Allocation: 0–100.",
        "Hours per day: 0–24. Points must be nonnegative.",
        "Import is atomic: correct all row errors before any data is saved.",
        "Capacity defaults to 8 hours per point; edit sprint hoursPerPoint to change.",
      };
      for (int i = 0; i < text.length; i++) instructions
        .createRow(i)
        .createCell(0)
        .setCellValue(text[i]);
      instructions.setColumnWidth(0, 25000);
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot create workbook");
    }
  }

  @Transactional
  public Map<String, Object> load(InputStream input, String actor) {
    var errors = new ArrayList<Map<String, Object>>();
    var pending = new ArrayList<Map.Entry<String, ObjectNode>>();
    try (var wb = WorkbookFactory.create(input)) {
      var formatter = new DataFormatter();
      for (var entry : COLUMNS.entrySet()) {
        var sheet = wb.getSheet(entry.getKey());
        if (sheet == null) {
          errors.add(
            Map.of(
              "sheet",
              entry.getKey(),
              "row",
              1,
              "message",
              "Missing sheet"
            )
          );
          continue;
        }
        String[] cols = entry.getValue();
        for (int c = 0; c < cols.length; c++) if (
          !formatter
            .formatCellValue(
              sheet.getRow(0) == null ? null : sheet.getRow(0).getCell(c)
            )
            .equals(cols[c].split(":")[0])
        ) errors.add(
          Map.of(
            "sheet",
            entry.getKey(),
            "row",
            1,
            "message",
            "Unexpected header " + (c + 1)
          )
        );
        if (sheet.getLastRowNum() > 5000) throw new IllegalArgumentException(
          "Maximum 5000 rows per sheet"
        );
        for (int row = 1; row <= sheet.getLastRowNum(); row++) {
          var rr = sheet.getRow(row);
          if (rr == null) continue;
          var data = store.parse("{}");
          boolean blank = true;
          for (int c = 0; c < cols.length; c++) {
            String v = formatter.formatCellValue(rr.getCell(c)).trim();
            if (!v.isEmpty()) blank = false;
            data.put(cols[c].split(":")[1], v);
          }
          if (blank) continue;
          try {
            validate(entry.getKey(), data);
            String kind = KINDS.get(entry.getKey());
            var old = store
              .list(kind)
              .stream()
              .filter(x -> identity(kind, x).equals(identity(kind, data)))
              .findFirst();
            if (old.isPresent()) {
              data.put("id", old.get().path("id").asText());
              data.put("revision", old.get().path("revision").asInt());
            }
            if (
              pending
                .stream()
                .anyMatch(
                  x ->
                    x.getKey().equals(kind) &&
                    identity(kind, x.getValue()).equals(identity(kind, data))
                )
            ) throw new IllegalArgumentException("Duplicate row identity");
            pending.add(Map.entry(kind, data));
          } catch (Exception e) {
            errors.add(
              Map.of(
                "sheet",
                entry.getKey(),
                "row",
                row + 1,
                "message",
                e.getMessage()
              )
            );
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read XLSX workbook");
    }
    if (!errors.isEmpty()) return Map.of("imported", 0, "errors", errors);
    for (var entry : pending)
      store.save(entry.getKey(), entry.getValue(), actor);
    return Map.of("imported", pending.size(), "errors", List.of());
  }

  private String identity(String kind, ObjectNode n) {
    return switch (kind) {
      case "stories" -> n.path("key").asText();
      case "leaves" -> n.path("name").asText() +
        n.path("from").asText() +
        n.path("to").asText();
      case "holidays" -> n.path("date").asText() +
        n.path("region").asText() +
        n.path("name").asText();
      default -> n.path("name").asText();
    };
  }

  private void validate(String sheet, ObjectNode n) {
    String required = sheet.equals("Stories")
      ? "key"
      : sheet.equals("Holidays")
        ? "date"
        : "name";
    if (n.path(required).asText().isBlank()) throw new IllegalArgumentException(
      required + " is required"
    );
    for (String date : List.of("date", "start", "end", "from", "to"))
      if (n.has(date)) LocalDate.parse(n.path(date).asText());
    if (
      (n.has("start") &&
        n.path("end").asText().compareTo(n.path("start").asText()) < 0) ||
      (n.has("from") &&
        n.path("to").asText().compareTo(n.path("from").asText()) < 0)
    ) throw new IllegalArgumentException("End date precedes start date");
    for (String field : List.of(
      "devPoints",
      "testPoints",
      "hoursPerDay",
      "allocation"
    ))
      if (n.has(field)) {
        double v = Double.parseDouble(n.path(field).asText());
        if (
          !Double.isFinite(v) ||
          v < 0 ||
          (field.equals("hoursPerDay") && v > 24) ||
          (field.equals("allocation") && v > 100)
        ) throw new IllegalArgumentException("Invalid " + field);
        n.put(field, v);
      }
    if (
      n.has("role") && !Set.of("Dev", "Test").contains(n.path("role").asText())
    ) throw new IllegalArgumentException("Role must be Dev or Test");
    if (
      n.has("region") &&
      !Set.of("USA", "India").contains(n.path("region").asText())
    ) throw new IllegalArgumentException("Region must be USA or India");
    if (
      n.has("priority") &&
      !Set.of("High", "Medium", "Low").contains(n.path("priority").asText())
    ) throw new IllegalArgumentException("Invalid priority");
  }
}
