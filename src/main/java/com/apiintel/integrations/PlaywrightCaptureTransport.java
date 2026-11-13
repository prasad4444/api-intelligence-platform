package com.apiintel.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Playwright-backed capture transport. Chromium must be installed explicitly
 * ({@code mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"}).
 * Interactive mode is for local Windows installs where the user completes SSO/MFA in a headed browser.
 */
public class PlaywrightCaptureTransport implements DiscoveryService.CaptureTransport {

  private final ObjectMapper json = new ObjectMapper();

  @Override
  public DiscoveryService.CaptureResult capture(
    String url,
    boolean interactive,
    BooleanSupplier cancelled,
    Consumer<String> approveSubrequest
  ) throws Exception {
    try {
      Class.forName("com.microsoft.playwright.Playwright");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
        "Playwright is not on the classpath. Add the dependency and install Chromium before discovery."
      );
    }
    try (var playwright = com.microsoft.playwright.Playwright.create()) {
      var browser = playwright.chromium().launch(
        new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(!interactive)
      );
      try {
        var context = browser.newContext(
          new com.microsoft.playwright.Browser.NewContextOptions().setIgnoreHTTPSErrors(false)
        );
        var page = context.newPage();
        List<ObjectNode> captures = Collections.synchronizedList(new ArrayList<>());
        page.onRequest(request -> {
          if (cancelled.getAsBoolean()) return;
          try {
            approveSubrequest.accept(request.url());
          } catch (RuntimeException blocked) {
            return;
          }
        });
        page.onResponse(response -> {
          if (cancelled.getAsBoolean() || captures.size() >= DiscoveryService.MAX_CAPTURES) return;
          var request = response.request();
          String resource = request.resourceType();
          if (!Set.of("xhr", "fetch").contains(resource)) return;
          try {
            approveSubrequest.accept(request.url());
          } catch (RuntimeException blocked) {
            return;
          }
          ObjectNode capture = json.createObjectNode();
          capture.put("method", request.method());
          capture.put("url", request.url());
          URIParts parts = URIParts.parse(request.url());
          capture.put("query", parts.query());
          var headers = capture.putObject("headers");
          request.headers().forEach((k, v) -> {
            if (k.equalsIgnoreCase("cookie")) capture.put("cookieHeader", v);
            else headers.put(k, v);
          });
          String post = request.postData();
          if (post != null) {
            if (post.length() > DiscoveryService.MAX_BODY) {
              capture.put("body", post.substring(0, DiscoveryService.MAX_BODY));
              capture.put("truncated", true);
            } else capture.put("body", post);
          }
          capture.put("status", response.status());
          capture.put("contentType", response.headerValue("content-type") == null ? "" : response.headerValue("content-type"));
          captures.add(capture);
        });
        page.navigate(url, new com.microsoft.playwright.Page.NavigateOptions().setTimeout(60000));
        if (interactive) {
          page.waitForTimeout(15000);
        } else {
          page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
            new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(30000));
        }
        if (cancelled.getAsBoolean()) throw new IllegalStateException("Discovery cancelled");
        String session = json.writeValueAsString(Map.of(
          "cookies", context.cookies(),
          "userAgent", page.evaluate("() => navigator.userAgent")
        ));
        return new DiscoveryService.CaptureResult(captures, session, true);
      } finally {
        browser.close();
      }
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
      if (message.contains("login") || message.contains("auth") || message.contains("sign in")) {
        throw new DiscoveryService.DiscoveryReconnectException("Reconnect required: complete interactive sign-in");
      }
      throw e;
    }
  }

  private record URIParts(String query) {
    static URIParts parse(String url) {
      try {
        var uri = java.net.URI.create(url);
        return new URIParts(uri.getRawQuery() == null ? "" : uri.getRawQuery());
      } catch (Exception e) {
        return new URIParts("");
      }
    }
  }
}
