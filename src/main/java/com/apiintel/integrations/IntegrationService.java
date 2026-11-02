package com.apiintel.integrations;

import com.apiintel.network.NetworkGuard;
import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

@Service
public class IntegrationService {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.context.ApplicationEventPublisher publisher;

  public record PullRequestCreated(ObjectNode payload, String actor) {}

  private final RecordStore store;
  private final Vault vault;
  private final NetworkGuard guard;
  private final Set<String> hosts;
  private final String friday;

  public IntegrationService(
    RecordStore s,
    Vault v,
    NetworkGuard g,
    @Value("${platform.integration-hosts}") String hosts,
    @Value("${platform.friday-url}") String friday
  ) {
    store = s;
    vault = v;
    guard = g;
    this.hosts = new HashSet<>(Arrays.asList(hosts.split(",")));
    this.friday = friday;
  }

  public ObjectNode config(String id) {
    return store.get("integrations", id);
  }

  public void authorize(String id) {
    var a =
      org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    if (
      a == null
    ) throw new org.springframework.security.access.AccessDeniedException(
      "Authentication required"
    );
    if (
      a
        .getAuthorities()
        .stream()
        .anyMatch(r -> r.getAuthority().equals("ROLE_ADMIN"))
    ) return;
    var c = config(id);
    for (var role : c.path("allowedRoles"))
      if (
        a
          .getAuthorities()
          .stream()
          .anyMatch(r -> r.getAuthority().equals("ROLE_" + role.asText()))
      ) return;
    throw new org.springframework.security.access.AccessDeniedException(
      "Your role cannot use this connection"
    );
  }

  public ObjectNode save(ObjectNode c, String actor) {
    String type = c.path("authType").asText("NONE");
    if (
      !Set.of("NONE", "BEARER", "BASIC", "API_KEY", "OAUTH2_CLIENT").contains(
        type
      )
    ) throw new IllegalArgumentException("Unsupported authentication type");
    if (
      c.has("token") || c.has("password") || c.has("clientSecret")
    ) throw new IllegalArgumentException(
      "Store credentials in the vault, then reference their name"
    );
    target(c, "");
    return store.save("integrations", c, actor);
  }

  private URI target(ObjectNode c, String path) {
    String base = c.path("baseUrl").asText();
    URI root = URI.create(base);
    if (
      root.getHost() == null ||
      root.getUserInfo() != null ||
      root.getQuery() != null ||
      root.getFragment() != null
    ) throw new IllegalArgumentException("Invalid base URL");
    if (
      !path.isEmpty() &&
      (!path.startsWith("/") ||
        path.contains("..") ||
        path.contains("\\") ||
        path.contains("#"))
    ) throw new IllegalArgumentException("Use a relative endpoint path");
    boolean localFriday =
      c.path("provider").asText().equals("friday") && base.equals(friday);
    if (!localFriday) {
      if (!hosts.contains(root.getHost())) throw new IllegalArgumentException(
        "Host must be enabled by INTEGRATION_HOSTS on the server"
      );
      guard.validate(base);
    } else if (
      !Set.of("http", "https").contains(root.getScheme())
    ) throw new IllegalArgumentException("Invalid Friday scheme");
    URI uri = URI.create(base.replaceAll("/$", "") + path);
    if (
      !Objects.equals(root.getHost(), uri.getHost()) ||
      root.getPort() != uri.getPort()
    ) throw new IllegalArgumentException(
      "Endpoint must stay on the configured host"
    );
    return uri;
  }

  private WebClient client(ObjectNode c) {
    boolean local =
      c.path("provider").asText().equals("friday") &&
      c.path("baseUrl").asText().equals(friday);
    var http = HttpClient.create()
      .followRedirect(false)
      .responseTimeout(Duration.ofSeconds(30))
      .doOnConnected(x -> {
        if (!local) guard.check(
          ((InetSocketAddress) x.channel().remoteAddress()).getAddress()
        );
      });
    return WebClient.builder()
      .clientConnector(new ReactorClientHttpConnector(http))
      .codecs(x -> x.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
      .build();
  }

  private void auth(ObjectNode c, HttpHeaders h) {
    String type = c.path("authType").asText("NONE");
    String secret = type.equals("NONE")
      ? ""
      : vault.get(c.path("secretRef").asText());
    if (
      !type.equals("NONE") && secret.isBlank()
    ) throw new IllegalArgumentException(
      "Add the credential in the vault first"
    );
    switch (type) {
      case "BEARER" -> h.setBearerAuth(secret);
      case "BASIC" -> h.set(
        "Authorization",
        "Basic " +
          Base64.getEncoder().encodeToString(
            secret.getBytes(StandardCharsets.UTF_8)
          )
      );
      case "API_KEY" -> {
        String header = c.path("headerName").asText("X-API-Key");
        if (
          !header.matches("(?i)(x-[a-z0-9-]+|api-key)")
        ) throw new IllegalArgumentException(
          "API key header must be X-* or api-key"
        );
        h.set(header, secret);
      }
      case "OAUTH2_CLIENT" -> {
        String path = c.path("tokenPath").asText("/oauth/token");
        var form = new org.springframework.util.LinkedMultiValueMap<
          String,
          String
        >();
        form.add("grant_type", "client_credentials");
        form.add("client_id", c.path("clientId").asText());
        form.add("client_secret", secret);
        form.add("scope", c.path("scope").asText());
        var response = client(c)
          .post()
          .uri(target(c, path))
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .bodyValue(form)
          .retrieve()
          .bodyToMono(JsonNode.class)
          .block(Duration.ofSeconds(35));
        if (
          response == null || !response.hasNonNull("access_token")
        ) throw new IllegalArgumentException(
          "Token endpoint returned no access_token"
        );
        h.setBearerAuth(response.path("access_token").asText());
      }
      default -> {
      }
    }
  }

  public JsonNode call(
    ObjectNode c,
    String method,
    String path,
    JsonNode payload,
    String actor
  ) {
    if (!c.path("enabled").asBoolean()) throw new IllegalArgumentException(
      "Connection is disabled"
    );
    var uri = target(c, path);
    store.audit(actor, "integration:" + method, c.path("id").asText());
    try {
      var request = client(c)
        .method(HttpMethod.valueOf(method))
        .uri(uri)
        .headers(h -> {
          auth(c, h);
          h.setContentType(MediaType.APPLICATION_JSON);
          h.set("Accept", "application/json");
          if (c.path("provider").asText().equals("anthropic")) h.set(
            "anthropic-version",
            "2023-06-01"
          );
        });
      String raw = request
        .bodyValue(payload == null ? "" : payload.toString())
        .retrieve()
        .bodyToMono(String.class)
        .defaultIfEmpty("{}")
        .block(Duration.ofSeconds(35));
      try {
        return new ObjectMapper().readTree(raw);
      } catch (Exception e) {
        return TextNode.valueOf(raw);
      }
    } catch (WebClientResponseException e) {
      throw new IllegalArgumentException(
        "Provider returned HTTP " +
          e.getStatusCode().value() +
          "; verify credentials and scopes"
      );
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(
        "Provider unavailable or request timed out"
      );
    }
  }

  public Flux<String> stream(ObjectNode c, String path, JsonNode payload) {
    target(c, path);
    if (!c.path("enabled").asBoolean()) throw new IllegalArgumentException(
      "Connection is disabled"
    );
    return client(c)
      .post()
      .uri(target(c, path))
      .headers(h -> auth(c, h))
      .contentType(MediaType.APPLICATION_JSON)
      .accept(MediaType.TEXT_EVENT_STREAM)
      .bodyValue(payload.toString())
      .retrieve()
      .bodyToFlux(String.class)
      .timeout(Duration.ofSeconds(60));
  }

  public ObjectNode propose(String id, ObjectNode action, String actor) {
    config(id);
    String method = action.path("method").asText("POST");
    if (
      !Set.of("POST", "PUT", "PATCH", "DELETE").contains(method)
    ) throw new IllegalArgumentException("Only write actions require approval");
    target(config(id), action.path("path").asText());
    var event = store.parse("{}");
    event.put("integrationId", id);
    event.put("connectionRevision", config(id).path("revision").asInt());
    event.put("status", "PENDING");
    event.put("requester", actor);
    event.set("action", action);
    return store.save("events", event, actor);
  }

  public synchronized ObjectNode approve(String id, String actor) {
    var e = store.get("events", id);
    if (
      !e.path("status").asText().equals("PENDING")
    ) throw new IllegalArgumentException("Approval is no longer pending");
    e.put("status", "APPROVED");
    e.put("approver", actor);
    return store.save("events", e, actor);
  }

  public synchronized Flux<String> executeStream(String id, String actor) {
    var e = store.get("events", id);
    var c = config(e.path("integrationId").asText());
    var action = e.path("action");
    if (
      !c.path("provider").asText().equals("friday") ||
      !action.path("method").asText().equals("POST") ||
      !action
        .path("path")
        .asText()
        .matches("/api/workspaces/[^/]+/signals/[^/]+")
    ) throw new IllegalArgumentException(
      "Streaming execution supports Friday signals only"
    );
    if (
      !e.path("status").asText().equals("APPROVED")
    ) throw new IllegalArgumentException("Explicit approval is required");
    if (
      c.path("revision").asInt() != e.path("connectionRevision").asInt()
    ) throw new IllegalArgumentException(
      "Connection changed; request a new approval"
    );
    e.put("status", "EXECUTING");
    final var claimed = store.save("events", e, actor);
    store.audit(actor, "friday:signal", id);
    try {
      return stream(c, action.path("path").asText(), action.path("body"))
        .doOnComplete(() -> finishStream(claimed, "COMPLETED", actor))
        .doOnError(x -> finishStream(claimed, "FAILED", actor))
        .doFinally(signal -> {
          if (signal == reactor.core.publisher.SignalType.CANCEL) finishStream(
            claimed,
            "CANCELLED",
            actor
          );
        });
    } catch (Exception x) {
      finishStream(claimed, "FAILED", actor);
      throw x;
    }
  }

  private void finishStream(ObjectNode event, String status, String actor) {
    event.put("status", status);
    store.save("events", event, actor);
  }

  public synchronized JsonNode execute(String id, String actor) {
    var e = store.get("events", id);
    if (
      !e.path("status").asText().equals("APPROVED")
    ) throw new IllegalArgumentException("Explicit approval is required");
    var c = config(e.path("integrationId").asText());
    if (
      c.path("revision").asInt() != e.path("connectionRevision").asInt()
    ) throw new IllegalArgumentException(
      "Connection changed; request a new approval"
    );
    e.put("status", "EXECUTING");
    e = store.save("events", e, actor);
    try {
      var a = e.path("action");
      var result = call(
        c,
        a.path("method").asText(),
        a.path("path").asText(),
        a.path("body"),
        actor
      );
      if (
        publisher != null &&
        result instanceof ObjectNode &&
        a.path("path").asText().matches("/repos/[^/]+/[^/]+/pulls")
      ) {
        try {
          publisher.publishEvent(
            new PullRequestCreated((ObjectNode) result, actor)
          );
        } catch (Exception followup) {
          store.audit(actor, "followup:enqueue-failed", id);
        }
      }
      e.put("status", "COMPLETED");
      store.save("events", e, actor);
      return result;
    } catch (Exception x) {
      e.put("status", "FAILED");
      store.save("events", e, actor);
      throw x;
    }
  }
}
