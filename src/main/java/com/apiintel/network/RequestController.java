package com.apiintel.network;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/client")
public class RequestController {

  private final RequestService service;

  public RequestController(RequestService s) {
    service = s;
  }

  @PostMapping("/send")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','TESTER')")
  public Object send(@RequestBody ObjectNode data, Principal p) {
    return service.send(data, p.getName());
  }
}
