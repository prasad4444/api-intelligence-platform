package com.apiintel.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runnable discovery command that shares {@link PlaywrightCaptureTransport} with the application flow.
 *
 * <pre>
 * mvn -q exec:java -Dexec.mainClass=com.apiintel.integrations.DiscoveryCommand -Dexec.args="https://example.com"
 * </pre>
 */
public class DiscoveryCommand {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: DiscoveryCommand <url> [--interactive]");
      System.exit(2);
    }
    String url = args[0];
    boolean interactive = args.length > 1 && "--interactive".equals(args[1]);
    var transport = new PlaywrightCaptureTransport();
    AtomicBoolean cancelled = new AtomicBoolean(false);
    var result = transport.capture(
      url,
      interactive,
      cancelled::get,
      sub -> {
        // CLI mode: allow the provided host tree only; the server enforces INTEGRATION_HOSTS.
        if (sub == null || sub.isBlank()) throw new IllegalArgumentException("Blocked empty URL");
      }
    );
    ObjectMapper json = new ObjectMapper();
    ObjectNode out = json.createObjectNode();
    out.put("browserUsed", result.browserUsed());
    out.put("captureCount", result.captures().size());
    out.set("captures", json.valueToTree(result.captures()));
    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
  }
}
