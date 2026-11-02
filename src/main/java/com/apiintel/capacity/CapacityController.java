package com.apiintel.capacity;

import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/capacity")
public class CapacityController {

  private final com.apiintel.integrations.FridayEvents friday;
  private final CapacityService capacity;
  private final WorkbookService workbooks;

  public CapacityController(
    CapacityService c,
    WorkbookService w,
    com.apiintel.integrations.FridayEvents friday
  ) {
    this.friday = friday;
    capacity = c;
    workbooks = w;
  }

  @PostMapping("/{sprint}/notify")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object notify(@PathVariable String sprint, Principal p) {
    var rows = capacity.calculate(sprint);
    var payload =
      new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    payload.put("sprintId", sprint);
    boolean over = rows
      .stream()
      .anyMatch(
        r ->
          ((Number) r.get("committed")).doubleValue() >
          ((Number) r.get("capacity")).doubleValue()
      );
    int queued = over
      ? friday.emit("sprintOverCapacity", payload, p.getName())
      : 0;
    return Map.of("overCapacity", over, "queued", queued);
  }

  @GetMapping("/{sprint}")
  public Object calculate(@PathVariable String sprint) {
    return capacity.calculate(sprint);
  }

  @GetMapping("/workbook/{mode}")
  public ResponseEntity<byte[]> workbook(@PathVariable String mode) {
    return ResponseEntity.ok()
      .header(
        "Content-Disposition",
        "attachment; filename=capacity-" + mode + ".xlsx"
      )
      .contentType(
        MediaType.parseMediaType(
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
      )
      .body(workbooks.export(mode.equals("template")));
  }

  @PostMapping("/workbook")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object upload(@RequestParam("file") MultipartFile file, Principal p)
    throws java.io.IOException {
    return workbooks.load(file.getInputStream(), p.getName());
  }
}
