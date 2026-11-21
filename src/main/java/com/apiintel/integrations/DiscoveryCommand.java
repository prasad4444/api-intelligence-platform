package com.apiintel.integrations;

import com.apiintel.studio.BrowserLauncher;
import com.apiintel.studio.PlaywrightBrowserLauncher;
import com.apiintel.studio.RecorderScript;
import com.apiintel.studio.StudioService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runnable discovery command that drives the same studio/recorder code path as
 * Connection Studio ({@link PlaywrightBrowserLauncher} +
 * {@link RecorderScript}), optionally through a running application's
 * {@link StudioService} so captures land on a real connection.
 *
 * <pre>
 * mvn -q exec:java -Dexec.mainClass=com.apiintel.integrations.DiscoveryCommand \
 *   -Dexec.args="https://example.com --interactive --user alice --connection &lt;id&gt;"
 * </pre>
 */
public class DiscoveryCommand {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println(
        "Usage: DiscoveryCommand <url> [--interactive] [--user <username> --connection <id>]");
      System.exit(2);
    }
    String url = args[0];
    boolean interactive = false;
    String user = null;
    String connectionId = null;
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--interactive" -> interactive = true;
        case "--user" -> user = args[++i];
        case "--connection" -> connectionId = args[++i];
        default -> { }
      }
    }
    ObjectMapper json = new ObjectMapper();
    var captures = new ArrayList<ObjectNode>();
    var actions = new ArrayList<ObjectNode>();
    AtomicBoolean cancelled = new AtomicBoolean(false);

    if (user != null && connectionId != null) {
      // Studio path on a live application context: session, captures and
      // actions are persisted against the user's own connection.
      var context = new org.springframework.boot.builder.SpringApplicationBuilder(
          com.apiintel.PlatformApplication.class)
        .properties(java.util.Map.of("server.port", "0"))
        .run();
      try {
        var studio = context.getBean(StudioService.class);
        var session = studio.start(json.createObjectNode()
          .put("connectionId", connectionId).put("url", url), user);
        String sessionId = session.path("id").asText();
        System.out.println("Studio session " + sessionId + " opening; sign in, then press Enter to record.");
        awaitEnter(cancelled);
        studio.signedIn(sessionId, user);
        studio.recordStart(sessionId, user, "");
        System.out.println("Recording. Press Enter to stop.");
        awaitEnter(cancelled);
        studio.recordStop(sessionId, user);
        var finalSession = studio.get(sessionId, user);
        finalSession.path("captures").forEach(c -> captures.add((ObjectNode) c));
        finalSession.path("actions").forEach(a -> actions.add((ObjectNode) a));
      } finally {
        context.close();
      }
    } else {
      // Standalone CLI capture through the studio launcher + recorder.
      var connection = json.createObjectNode();
      connection.put("browserProfileDir",
        java.nio.file.Path.of("./data/browser-profiles/cli/discovery").toString());
      var launcher = new PlaywrightBrowserLauncher();
      var handle = launcher.open(new BrowserLauncher.OpenRequest(
        connection, !interactive,
        u -> { },
        captures::add,
        actions::add,
        cancelled::get));
      try {
        handle.evaluate("location.assign(" + json.writeValueAsString(url) + ")");
        handle.installRecorder(RecorderScript.script(""));
        System.out.println("Browser opened" + (interactive ? " for sign-in" : "")
          + ". Press Enter to stop capturing.");
        awaitEnter(cancelled);
      } finally {
        handle.close();
      }
    }

    ObjectNode out = json.createObjectNode();
    out.put("browserUsed", true);
    out.put("captureCount", captures.size());
    out.put("actionCount", actions.size());
    out.set("captures", json.valueToTree(captures));
    out.set("actions", json.valueToTree(actions));
    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
  }

  private static void awaitEnter(AtomicBoolean cancelled) {
    try {
      while (System.in.read() != '\n') {
        if (cancelled.get()) return;
      }
      cancelled.set(true);
    } catch (java.io.IOException e) {
      cancelled.set(true);
    }
  }
}
