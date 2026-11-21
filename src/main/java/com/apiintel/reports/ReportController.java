package com.apiintel.reports;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

  private final ReportService reports;
  private final ReportExports exports;

  public ReportController(ReportService reports, ReportExports exports) {
    this.reports = reports;
    this.exports = exports;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> list(Principal p) {
    return reports.definitions(p.getName());
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode save(@RequestBody ObjectNode input, Principal p) {
    return reports.saveDefinition(input, p.getName());
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode get(@PathVariable String id, Principal p) {
    return reports.definition(id, p.getName());
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public void delete(@PathVariable String id, Principal p) {
    reports.deleteDefinition(id, p.getName());
  }

  @PostMapping("/preview")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode preview(@RequestBody ObjectNode input, Principal p) {
    return reports.preview(input.path("reportId").asText(null), input, p.getName());
  }

  @PostMapping("/{id}/run")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode run(@PathVariable String id, Principal p) {
    return reports.run(id, Instant.now(), null, p.getName());
  }

  @GetMapping("/{id}/snapshots")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> snapshots(@PathVariable String id, Principal p) {
    return reports.snapshots(id, p.getName());
  }

  @GetMapping("/snapshots/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode snapshot(@PathVariable String id, Principal p) {
    return reports.snapshot(id, p.getName());
  }

  @GetMapping("/snapshots/{id}.csv")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ResponseEntity<byte[]> csv(@PathVariable String id, Principal p) {
    byte[] body = exports.csv(reports.snapshot(id, p.getName()));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"snapshot-" + id + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }

  @GetMapping("/snapshots/{id}.pdf")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ResponseEntity<byte[]> pdf(@PathVariable String id, Principal p) {
    byte[] body = exports.pdf(reports.snapshot(id, p.getName()));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"snapshot-" + id + ".pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(body);
  }
}
