package com.apiintel.store;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoData implements CommandLineRunner {

  private final RecordStore store;
  private final boolean enabled;

  public DemoData(RecordStore s, @Value("${platform.seed}") boolean e) {
    store = s;
    enabled = e;
  }

  public void run(String... args) {
    if (!enabled || !store.list("specs").isEmpty()) return;
    store.save(
      "specs",
      store.parse(
        """
        {"name":"Commerce API","version":"1.2.0","status":"DRAFT","document":{"openapi":"3.0.3","info":{"title":"Commerce API","version":"1.2.0","description":"Demo order management contract"},"servers":[{"url":"https://httpbin.org"}],"security":[],"paths":{"/get":{"get":{"summary":"List orders","operationId":"listOrders","responses":{"200":{"description":"Order list"}}}},"/post":{"post":{"summary":"Create an order","operationId":"createOrder","responses":{"200":{"description":"Order accepted"}}}}}}}
        """
      ),
      "seed"
    );
    store.save(
      "collections",
      store.parse(
        """
        {"name":"Commerce smoke tests","info":{"name":"Commerce smoke tests","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},"item":[{"name":"List orders","request":{"method":"GET","url":"{{baseUrl}}/get","header":[]}}]}
        """
      ),
      "seed"
    );
    store.save(
      "team",
      store.parse(
        "{\"name\":\"Alex Morgan\",\"role\":\"Dev\",\"region\":\"USA\",\"hoursPerDay\":8,\"allocation\":80}"
      ),
      "seed"
    );
    store.save(
      "team",
      store.parse(
        "{\"name\":\"Priya Shah\",\"role\":\"Test\",\"region\":\"India\",\"hoursPerDay\":8,\"allocation\":100}"
      ),
      "seed"
    );
    LocalDate start = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
    var sprint = store.parse("{\"name\":\"Sprint 01\",\"hoursPerPoint\":8}");
    sprint.put("start", start.toString());
    sprint.put("end", start.plusDays(11).toString());
    store.save("sprints", sprint, "seed");
    for (int i = 1; i <= 4; i++) store.save(
      "stories",
      store.parse(
        "{\"key\":\"DEMO-" +
          i +
          "\",\"summary\":\"" +
          new String[] {
            "",
            "Order validation",
            "Contract coverage",
            "Payment error handling",
            "Release readiness",
          }[i] +
          "\",\"priority\":\"High\",\"status\":\"" +
          (i == 1 ? "Done" : i == 2 ? "In Progress" : "To Do") +
          "\",\"sprint\":\"Sprint 01\",\"devPoints\":3,\"testPoints\":2,\"developer\":\"Alex Morgan\",\"tester\":\"Priya Shah\"}"
      ),
      "seed"
    );
    store.save(
      "diagrams",
      store.parse(
        "{\"name\":\"Request lifecycle\",\"source\":\"flowchart TD\\n A[Client] --> B[API Gateway]\\n B --> C[Orders]\\n B --> D[Payments]\\n C --> E[(Database)]\\n D --> E\"}"
      ),
      "seed"
    );
    for (String provider : new String[] {
      "github",
      "jira",
      "devin",
      "openai",
      "anthropic",
      "azure",
      "ollama",
      "friday",
      "custom",
    }) {
      String base = switch (provider) {
        case "github" -> "https://api.github.com";
        case "devin" -> "https://api.devin.ai";
        case "openai" -> "https://api.openai.com";
        case "anthropic" -> "https://api.anthropic.com";
        case "friday" -> "http://localhost:18080";
        case "ollama" -> "http://localhost:11434";
        default -> "https://example.com";
      };
      var c = store.parse("{}");
      c.put(
        "name",
        provider.substring(0, 1).toUpperCase() + provider.substring(1)
      );
      c.put("provider", provider);
      c.put("baseUrl", base);
      c.put(
        "authType",
        provider.equals("friday") || provider.equals("ollama")
          ? "NONE"
          : provider.equals("anthropic")
            ? "API_KEY"
            : "BEARER"
      );
      c.put("secretRef", provider + "-token");
      c.put("headerName", "x-api-key");
      c.put("enabled", false);
      c.put("organizationId", "");
      c.putArray("allowedRoles").add("ADMIN");
      store.save("integrations", c, "seed");
    }
  }
}
