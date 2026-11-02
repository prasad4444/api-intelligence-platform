package com.apiintel.tests;

import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tests")
public class TestController {

  private final com.apiintel.integrations.FridayEvents friday;
  private final TestService service;
  private final TestWorkbookService workbook;

  public TestController(
    TestService s,
    TestWorkbookService w,
    com.apiintel.integrations.FridayEvents friday
  ) {
    this.friday = friday;
    service = s;
    workbook = w;
  }

  @PostMapping("/generate/{specId}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object generate(@PathVariable String specId, Principal p) {
    return service.generate(specId, p.getName());
  }

  @PostMapping("/{id}/run")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object run(
    @PathVariable String id,
    @RequestBody Map<String, String> env,
    Principal p
  ) {
    var result = service.run(id, env, p.getName());
    if (result.path("status").asText().equals("FAIL")) friday.emit(
      "testsFailed",
      result,
      p.getName()
    );
    return result;
  }

  @PostMapping("/suite/{kind}/{id}/run")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object suite(
    @PathVariable String kind,
    @PathVariable String id,
    @RequestBody Map<String, String> env,
    Principal p
  ) {
    return service.runSuite(kind, id, env, p.getName());
  }

  @GetMapping("/workbook")
  public ResponseEntity<byte[]> workbook() {
    return ResponseEntity.ok()
      .header("Content-Disposition", "attachment; filename=test-cases.xlsx")
      .contentType(
        MediaType.parseMediaType(
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
      )
      .body(workbook.export());
  }

  @PostMapping("/workbook")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object importWorkbook(
    @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
    Principal p
  ) throws java.io.IOException {
    return workbook.load(file.getInputStream(), p.getName());
  }

  @GetMapping("/export/{format}")
  public ResponseEntity<String> export(@PathVariable String format) {
    return ResponseEntity.ok()
      .header(
        "Content-Disposition",
        "attachment; filename=tests." +
          (format.equals("junit")
            ? "java"
            : format.equals("cucumber")
              ? "feature"
              : format.equals("postman")
                ? "json"
                : format)
      )
      .contentType(MediaType.TEXT_PLAIN)
      .body(service.export(format));
  }
}
