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

  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private WindowsAuthTransport windows;
  private final RecordStore store;
  private final Vault vault;
  private final NetworkGuard guard;
  private final Set<String> hosts;
  private final String friday;
  @Value("${platform.integration-private-hosts:}")
  private String privateHosts = "";

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
    this.hosts = new HashSet<>();
    for (String host : hosts.split(",")) {
      if (!host.isBlank()) this.hosts.add(host.trim().toLowerCase(Locale.ROOT));
    }
    this.friday = friday;
  }

  public JsonNode chat(String id, String prompt, String actor) {
    authorize(id);
    if (prompt.length() > 20000) throw new IllegalArgumentException("Prompt is too large");
    return chat(config(id), prompt, actor);
  }

  JsonNode chat(ObjectNode c, String prompt, String actor) {
    String provider = c.path("provider").asText();
    if (prompt.length() > 180000) throw new IllegalArgumentException("Contract context is too large. Select a smaller scope");
    if (prompt.isBlank()) throw new IllegalArgumentException("Enter a prompt");
    if (c.path("aiChatEnabled").asBoolean()) {
      var response = call(c, "POST", c.path("chatPath").asText(), AiChat.request(c, prompt), actor);
      return store.parse("{}").set("response", AiChat.response(c, response));
    }
    var b = store.parse("{}");
    b.put("model", c.path("model").asText(""));
    String path;
    switch (provider) {
      case "openai", "azure" -> {
        var m = b.putArray("messages").addObject();
        m.put("role", "user");
        m.put("content", prompt);
        path = c.path("chatPath").asText("/v1/chat/completions");
      }
      case "anthropic" -> {
        b.put("max_tokens", 1024);
        var m = b.putArray("messages").addObject();
        m.put("role", "user");
        m.put("content", prompt);
        path = "/v1/messages";
      }
      case "ollama" -> {
        b.put("prompt", prompt);
        b.put("stream", false);
        path = "/api/generate";
      }
      default -> throw new IllegalArgumentException(
        "Use the connector action editor for this provider"
      );
    }
    return call(c, "POST", path, b, actor);
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
      !Set.of("NONE", "BEARER", "BASIC", "AUTHORIZATION", "JIRA_TOKEN", "API_KEY", "OAUTH2_CLIENT", "WINDOWS").contains(
        type
      )
    ) throw new IllegalArgumentException("Unsupported authentication type");
    if (
      c.has("token") || c.has("password") || c.has("clientSecret")
    ) throw new IllegalArgumentException(
      "Store credentials in the vault, then reference their name"
    );
    URI base = target(c, "");
    if (Set.of("AUTHORIZATION", "JIRA_TOKEN").contains(type) && !"https".equals(base.getScheme())) {
      throw new IllegalArgumentException("Use an HTTPS URL for this credential");
    }
    if (c.path("aiChatEnabled").asBoolean()) {
      AiChat.request(c, "Connection configuration check");
      target(c, c.path("chatPath").asText());
    }
    if (type.equals("JIRA_TOKEN") && (!c.path("provider").asText().equals("jira") ||
        c.path("jiraEmail").asText().isBlank())) {
      throw new IllegalArgumentException("Enter the Atlassian account email for a Jira Cloud API token");
    }
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
      if (!hosts.contains(root.getHost().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException(
        "Host must be enabled by INTEGRATION_HOSTS on the server"
      );
      if(c.path("authType").asText().equals("WINDOWS")) {
        if(windows==null)throw new IllegalArgumentException("Windows authentication transport is unavailable");
        windows.validate(c,root);
      } else if (privateTarget(root)) {
        try {
          for (var address : InetAddress.getAllByName(root.getHost())) checkAddress(root, address);
        } catch (UnknownHostException e) {
          throw new IllegalArgumentException("Host cannot be resolved");
        }
      } else guard.validate(base);
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

  private boolean privateTarget(URI root) {
    return "https".equals(root.getScheme()) && Arrays.stream(privateHosts.split(","))
      .anyMatch(host -> host.trim().equalsIgnoreCase(root.getHost()));
  }

  private void checkAddress(URI root, InetAddress address) {
    byte[] bytes = address.getAddress();
    boolean privateUnicast = bytes.length == 4 && address.isSiteLocalAddress()
      || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    if (!privateTarget(root) || !privateUnicast) guard.check(address);
  }

  private WebClient client(ObjectNode c) {
    boolean local =
      c.path("provider").asText().equals("friday") &&
      c.path("baseUrl").asText().equals(friday);
    var http = HttpClient.create()
      .followRedirect(false)
      .responseTimeout(Duration.ofSeconds(30))
      .doOnConnected(x -> {
        if (!local) checkAddress(URI.create(c.path("baseUrl").asText()),
          ((InetSocketAddress) x.channel().remoteAddress()).getAddress());
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
      case "BEARER" -> h.setBearerAuth(secret.trim().replaceFirst("(?i)^Bearer +", ""));
      case "AUTHORIZATION" -> h.set(HttpHeaders.AUTHORIZATION, secret.trim());
      case "JIRA_TOKEN" -> h.setBasicAuth(c.path("jiraEmail").asText().trim(), secret.trim(), StandardCharsets.UTF_8);
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
    if(c.path("authType").asText().equals("WINDOWS")) {
      if(windows==null)throw new IllegalArgumentException("Windows authentication transport is unavailable");
      return windows.call(c,uri,method,payload);
    }
    try {
      var credentials = new HttpHeaders();
      auth(c, credentials);
      var request = client(c)
        .method(HttpMethod.valueOf(method))
        .uri(uri)
        .headers(h -> {
          h.addAll(credentials);
          h.setContentType(MediaType.APPLICATION_JSON);
          h.set("Accept", c.path("aiResponseFormat").asText().equals("sse")
            ? "application/json, text/event-stream" : "application/json");
          if (c.path("provider").asText().equals("anthropic")) h.set(
            "anthropic-version",
            "2023-06-01"
          );
        });
      var response = payload == null ? request.retrieve() : request.bodyValue(payload.toString()).retrieve();
      String raw = response
        .onStatus(HttpStatusCode::is3xxRedirection, r -> reactor.core.publisher.Mono.just(
          new IllegalArgumentException("Provider redirected the request. Use the final API URL and verify the credential")
        ))
        .bodyToMono(String.class)
        .defaultIfEmpty("{}")
        .block(Duration.ofSeconds(35));
      for (var values : credentials.values()) for (String value : values) {
        if (value.isBlank()) continue;
        raw = redact(raw, value);
        if (value.matches("(?i)^(Bearer|Basic) .+")) raw = redact(raw, value.substring(value.indexOf(' ') + 1));
      }
      try {
        return new ObjectMapper().readTree(raw);
      } catch (Exception e) {
        return TextNode.valueOf(raw);
      }
    } catch (WebClientResponseException e) {
      String advice = switch (e.getStatusCode().value()) {
        case 401 -> "check the authorization scheme and replace expired credentials";
        case 403 -> "check token scopes and the account's permissions";
        case 404 -> "check the endpoint URL and API version";
        case 429 -> "the provider rate limit was reached";
        default -> "check the configured endpoint and request payload";
      };
      throw new IllegalArgumentException("Provider returned HTTP " + e.getStatusCode().value() + ". " + advice);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(
        "Provider unavailable or request timed out"
      );
    }
  }

  private String redact(String text, String secret) {
    String quoted = TextNode.valueOf(secret).toString();
    return text.replace(secret, "REDACTED").replace(quoted.substring(1, quoted.length() - 1), "REDACTED");
  }

  public Map<String,Object> checkWindows(String id) {
    var c=config(id);if(!c.path("authType").asText().equals("WINDOWS"))throw new IllegalArgumentException("Select Windows authentication for this connection first");
    target(c,"");if(windows==null)throw new IllegalArgumentException("Windows authentication transport is unavailable");return windows.check(c);
  }

  public Flux<String> stream(ObjectNode c, String path, JsonNode payload) {
    if(c.path("authType").asText().equals("WINDOWS"))throw new IllegalArgumentException("Streaming is not supported with Windows authentication; use a JSON request or an approved API token gateway");
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
