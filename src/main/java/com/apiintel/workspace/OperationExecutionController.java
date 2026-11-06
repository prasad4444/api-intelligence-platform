package com.apiintel.workspace;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspace")
public class OperationExecutionController {
  private final OperationExecution execution;

  public OperationExecutionController(OperationExecution execution) {
    this.execution = execution;
  }

  @PostMapping("/{id}/operations/execute")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public ObjectNode execute(@PathVariable String id, @RequestBody ObjectNode request, Principal principal) {
    return execution.execute(id, request, principal.getName());
  }
}
