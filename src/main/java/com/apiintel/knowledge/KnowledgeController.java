package com.apiintel.knowledge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

  private final KnowledgeService knowledge;

  public KnowledgeController(KnowledgeService knowledge) {
    this.knowledge = knowledge;
  }

  @GetMapping("/search")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public List<ObjectNode> search(@RequestParam(defaultValue = "") String q,
                                 @RequestParam(required = false) List<String> kinds,
                                 Principal p) {
    return knowledge.search(p.getName(), q, kinds);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode get(@PathVariable String id, Principal p) {
    return knowledge.getChecked(id, p.getName());
  }
}
