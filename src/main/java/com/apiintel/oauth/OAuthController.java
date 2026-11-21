package com.apiintel.oauth;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/oauth")
public class OAuthController {

  private final OAuthService oauth;

  public OAuthController(OAuthService oauth) {
    this.oauth = oauth;
  }

  @GetMapping("/{connectionId}/start")
  @PreAuthorize("isAuthenticated()")
  public ObjectNode start(@PathVariable String connectionId, Principal principal) {
    return oauth.start(connectionId, principal.getName());
  }

  /** Provider redirect target. The state token binds this to owner+connection. */
  @GetMapping("/callback")
  public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error) {
    if (error != null || code == null || state == null) {
      return redirect("/#connection-studio?error=" + (error == null ? "oauth_incomplete" : error));
    }
    String connectionId = oauth.callback(code, state);
    return redirect(oauth.successRedirect(connectionId));
  }

  private static ResponseEntity<Void> redirect(String location) {
    var response = new ResponseEntity<Void>(HttpStatus.FOUND);
    return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
  }
}
