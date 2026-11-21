package com.apiintel.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Production launcher: headed/persistent Chromium via Playwright with a
 * per-owner, per-connection profile directory so sign-in state survives
 * restarts. Excluded from the coverage gate (untestable browser glue), same
 * as PlaywrightCaptureTransport.
 */
@Component
public class PlaywrightBrowserLauncher implements BrowserLauncher {

  private final ObjectMapper json = new ObjectMapper();
  private volatile com.microsoft.playwright.Playwright playwright;

  @Override
  public BrowserHandle open(OpenRequest request) throws Exception {
    if (playwright == null) {
      synchronized (this) {
        if (playwright == null) playwright = com.microsoft.playwright.Playwright.create();
      }
    }
    String profile = request.connection().path("browserProfileDir").asText("");
    if (profile.isBlank()) throw new IllegalArgumentException("Missing browser profile directory");
    var context = playwright.chromium().launchPersistentContext(
      Path.of(profile),
      new com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions()
        .setHeadless(request.headless())
        .setIgnoreHTTPSErrors(false));
    var handle = new Handle(context);
    var page = context.newPage();
    handle.page = page;
    context.exposeBinding(RecorderScript.BINDING, (source, args) -> {
      try {
        var action = (ObjectNode) json.readTree(String.valueOf(args[0]));
        request.onAction().accept(action);
      } catch (Exception ignored) {
      }
      return null;
    });
    page.onResponse(response -> {
      if (request.cancelled().getAsBoolean()) return;
      var req = response.request();
      if (!Set.of("xhr", "fetch").contains(req.resourceType())) return;
      try {
        ObjectNode capture = json.createObjectNode();
        capture.put("method", req.method());
        capture.put("url", req.url());
        var uri = java.net.URI.create(req.url());
        capture.put("query", uri.getRawQuery() == null ? "" : uri.getRawQuery());
        var headers = capture.putObject("headers");
        req.headers().forEach((k, v) -> {
          if (k.equalsIgnoreCase("cookie")) capture.put("cookieHeader", v);
          else headers.put(k, v);
        });
        if (req.postData() != null) capture.put("body", req.postData());
        capture.put("status", response.status());
        capture.put("contentType", response.headerValue("content-type") == null ? "" : response.headerValue("content-type"));
        try {
          String body = response.text();
          if (body != null && body.length() > 0) {
            capture.put("responsePreview", body.length() > 20000 ? body.substring(0, 20000) : body);
            capture.put("sse", capture.path("contentType").asText().contains("event-stream"));
          }
        } catch (Exception ignored) {
        }
        request.onCapture().accept(capture);
      } catch (RuntimeException ignored) {
      }
    });
    return handle;
  }

  private class Handle implements BrowserHandle {
    private final com.microsoft.playwright.BrowserContext context;
    private com.microsoft.playwright.Page page;

    Handle(com.microsoft.playwright.BrowserContext context) {
      this.context = context;
    }

    @Override
    public String currentUrl() {
      return page == null ? "" : page.url();
    }

    @Override
    public Object evaluate(String script) {
      return page == null ? null : page.evaluate(script);
    }

    @Override
    public void installRecorder(String initScript) {
      context.addInitScript(initScript);
    }

    @Override
    public String storageState() {
      try {
        return json.writeValueAsString(Map.of("cookies", context.cookies()));
      } catch (Exception e) {
        return "{}";
      }
    }

    @Override
    public String identity() {
      return "";
    }

    @Override
    public void close() {
      try {
        context.close();
      } catch (Exception ignored) {
      }
    }
  }
}
