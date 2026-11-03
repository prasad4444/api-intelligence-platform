package com.apiintel.environments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/environments")
public class EnvironmentController {
  private final EnvironmentService service;public EnvironmentController(EnvironmentService service){this.service=service;}
  @GetMapping public Object list(){return service.list();}
  @PostMapping @PreAuthorize("hasRole('ADMIN')") public Object save(@RequestBody ObjectNode input,Principal p){return service.save(input,p.getName());}
  @DeleteMapping("/{id}") @PreAuthorize("hasRole('ADMIN')") public void delete(@PathVariable String id,Principal p){service.delete(id,p.getName());}
}
