package com.apiintel.tests;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/tests/generate/{specId}")
@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
public class TestGenerationController {
  private final TestGenerationService service;
  public TestGenerationController(TestGenerationService service){this.service=service;}
  @PostMapping("/package") public Object local(@PathVariable String specId,Principal p){return service.local(specId,p.getName());}
  @PostMapping("/preview") public Object preview(@PathVariable String specId,@RequestBody ObjectNode body,Principal p){return service.preview(specId,body,p.getName());}
  @PostMapping("/accept") public Object accept(@PathVariable String specId,@RequestBody ObjectNode body,Principal p){return service.accept(specId,body,p.getName());}
}
