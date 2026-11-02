package com.apiintel.tests;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TestWorkbookService {

  private final RecordStore store;

  public TestWorkbookService(RecordStore s) {
    store = s;
  }

  private static final String[] COLUMNS = {
    "title",
    "operation",
    "specId",
    "type",
    "priority",
    "status",
    "preconditions",
    "steps",
    "request",
    "expectedStatus",
    "expectedBodyContains",
    "tags",
    "linkedJiraKey",
    "author",
  };

  public byte[] export() {
    try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = wb.createSheet("Test Cases");
      var header = sheet.createRow(0);
      var font = wb.createFont();
      font.setBold(true);
      var style = wb.createCellStyle();
      style.setFont(font);
      for (int c = 0; c < COLUMNS.length; c++) {
        header.createCell(c).setCellValue(COLUMNS[c]);
        header.getCell(c).setCellStyle(style);
        sheet.setColumnWidth(c, 7000);
      }
      int row = 1;
      for (var test : store.list("tests")) {
        var r = sheet.createRow(row++);
        for (int c = 0; c < COLUMNS.length; c++) {
          var value = test.path(COLUMNS[c]);
          r.createCell(c).setCellValue(
            value.isContainerNode() ? value.toString() : value.asText()
          );
        }
      }
      sheet.createFreezePane(0, 1);
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot export test workbook");
    }
  }

  @Transactional
  public Object load(InputStream input, String actor) {
    var errors = new ArrayList<Map<String, Object>>();
    var rows = new ArrayList<ObjectNode>();
    try (var wb = new XSSFWorkbook(input)) {
      var sheet = wb.getSheet("Test Cases");
      if (sheet == null) throw new IllegalArgumentException(
        "Test Cases sheet is required"
      );
      if (sheet.getLastRowNum() > 5000) throw new IllegalArgumentException(
        "Maximum 5000 cases"
      );
      var format = new DataFormatter();
      for (int c = 0; c < COLUMNS.length; c++) if (
        sheet.getRow(0) == null ||
        !format.formatCellValue(sheet.getRow(0).getCell(c)).equals(COLUMNS[c])
      ) throw new IllegalArgumentException("Use the exported workbook headers");
      for (int i = 1; i <= sheet.getLastRowNum(); i++) {
        var row = sheet.getRow(i);
        if (row == null) continue;
        try {
          var data = store.parse("{}");
          for (int c = 0; c < COLUMNS.length; c++) {
            String value = format.formatCellValue(row.getCell(c));
            if (Set.of("request", "steps", "tags").contains(COLUMNS[c])) {
              if (!value.isBlank()) data.set(
                COLUMNS[c],
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                  value
                )
              );
            } else if (COLUMNS[c].equals("expectedStatus")) {
              data.put(COLUMNS[c], Integer.parseInt(value));
            } else data.put(COLUMNS[c], value);
          }
          if (
            data.path("title").asText().isBlank() ||
            !data.path("request").isObject()
          ) throw new IllegalArgumentException(
            "Title and request JSON are required"
          );
          if (
            !Set.of("positive", "negative", "boundary", "security").contains(
              data.path("type").asText()
            )
          ) throw new IllegalArgumentException("Invalid test type");
          if (
            data.path("expectedStatus").asInt() < 100 ||
            data.path("expectedStatus").asInt() > 599
          ) throw new IllegalArgumentException(
            "Expected status must be 100–599"
          );
          rows.add(data);
        } catch (Exception e) {
          errors.add(
            Map.of(
              "row",
              i + 1,
              "message",
              e.getMessage() == null ? "Invalid row" : e.getMessage()
            )
          );
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read test workbook");
    }
    if (!errors.isEmpty()) return Map.of("imported", 0, "errors", errors);
    for (var t : rows) store.save("tests", t, actor);
    return Map.of("imported", rows.size(), "errors", errors);
  }
}
