package com.apiintel.network;

import com.apiintel.store.RecordStore;
import com.apiintel.diagnostics.ApiRequestLog;
import com.apiintel.diagnostics.OutboundLog;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Service
public class RequestService {
  @org.springframework.beans.factory.annotation.Autowired
  private OutboundLog outbound = new OutboundLog();


  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private com.apiintel.environments.EnvironmentService environments;
  private final NetworkGuard guard;
  private final RecordStore store;

  public RequestService(NetworkGuard guard, RecordStore store) {
    this.guard = guard;
    this.store = store;
  }

  public ObjectNode send(ObjectNode input, String actor) {
    return send(input, actor, "request-studio");
  }

  public ObjectNode send(ObjectNode input, String actor, String source) {
    var sensitive = new HashSet<String>();
    try {
      if (environments != null) {
        var resolved = environments.resolve(input);
        input = resolved.request();
        sensitive.addAll(resolved.secrets());
      }
    } catch (RuntimeException error) {
      outbound.rejected(source, "credential_profile", error);
      throw error;
    }

    String method = input.path("method").asText("GET").toUpperCase(Locale.ROOT);
    URI uri;
    var headers = new HttpHeaders();
    try {
      if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD").contains(method)) {
        throw new IllegalArgumentException("Unsupported HTTP method");
      }
      uri = guard.validate(input.path("url").asText());
      input.path("headers").fields().forEachRemaining(header -> {
        String name = header.getKey();
        String value = header.getValue().asText();
        if (Set.of("host", "connection", "content-length", "transfer-encoding", "cookie", "proxy-authorization")
            .contains(name.toLowerCase(Locale.ROOT))) return;
        if (name.equalsIgnoreCase(HttpHeaders.AUTHORIZATION)) {
          value = value.strip().replaceFirst("(?i)^(?:Bearer\\s+){2,}", "Bearer ");
          if (value.equalsIgnoreCase("Bearer") || value.equalsIgnoreCase("Basic")) {
            throw new IllegalArgumentException("Authorization is missing a credential. Check the selected auth type and environment");
          }
          if (!value.isBlank()) {
            sensitive.add(value);
            String scheme = OutboundLog.scheme(value);
            if (Set.of("bearer", "basic", "dpop").contains(scheme) && value.length() > scheme.length() + 1) {
              sensitive.add(value.substring(scheme.length()).strip());
            }
          }
        }
        headers.set(name, value);
      });
    } catch (RuntimeException error) {
      outbound.rejected(source, "request_validation", error);
      throw error;
    }
    String authorization = OutboundLog.scheme(headers.getFirst(HttpHeaders.AUTHORIZATION));
    boolean credentialHeader = OutboundLog.hasCredentialHeader(headers);
    boolean profile = List.of("globalProfileId", "collectionProfileId", "environmentId").stream()
        .anyMatch(input::hasNonNull);
    boolean cookieDropped = false;
    var requestedHeaders = input.path("headers").fieldNames();
    while (requestedHeaders.hasNext()) if (requestedHeaders.next().equalsIgnoreCase("Cookie")) cookieDropped = true;
    String id = ApiRequestLog.requestId();
    long start = System.nanoTime();
    outbound.start(source, method, uri, authorization, credentialHeader, profile, cookieDropped);
    var client = HttpClient.create()
      .followRedirect(false)
      .responseTimeout(Duration.ofSeconds(15))
      .doOnConnected(c -> {
        guard.check(
          ((InetSocketAddress) c.channel().remoteAddress()).getAddress()
        );
      });
    var web = WebClient.builder()
      .clientConnector(new ReactorClientHttpConnector(client))
      .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
      .build();
    var result = store.parse("{}");
    var status = new AtomicInteger();
    var challenge = new AtomicReference<>("none");
    var challengeError = new AtomicReference<>("none");
    String reason;
    try {
      var response = web.method(HttpMethod.valueOf(method)).uri(uri).headers(h -> h.addAll(headers))
        .bodyValue(input.path("body").asText(""))
        .exchangeToMono(r -> {
          status.set(r.statusCode().value());
          challenge.set(OutboundLog.challenge(r.headers().asHttpHeaders()));
          challengeError.set(OutboundLog.challengeError(r.headers().asHttpHeaders()));
          return r.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
            var out = store.parse("{}");
            out.put("status", status.get()).put("body", body);
            var responseHeaders = out.putObject("headers");
            r.headers().asHttpHeaders().forEach((k, v) -> responseHeaders.put(k, String.join(", ", v)));
            return out;
          });
        }).block(Duration.ofSeconds(20));
      result.setAll(response);
      reason = OutboundLog.statusReason(status.get());
    } catch (Exception error) {
      reason = OutboundLog.failure(error);
      result.put("status", status.get());
      result.put("error", "Could not read a complete upstream response (" + reason + ")");
    }
    outbound.result(id, source, method, uri, status.get(), challenge.get(), start, challengeError.get().equals("none") ? reason : reason + ":" + challengeError.get());
    result.put("durationMs", OutboundLog.elapsed(start)).put("method", method);
    if (!id.equals("-")) result.put("requestId", id);
    if (result.has("error") || status.get() == 0 || status.get() >= 300) {
      result.putObject("diagnostic")
        .put("reason", status.get() == 0 ? reason : OutboundLog.statusReason(status.get()))
        .put("authorization", authorization)
        .put("credentialHeader", credentialHeader)
        .put("challenge", challenge.get())
        .put("challengeError", challengeError.get())
        .put("queryPresent", uri.getRawQuery() != null)
        .put("cookieDropped", cookieDropped);
    }
    String safeUrl = redact(uri.toString(), sensitive);
    if (!sensitive.isEmpty()) {
      if (result.has("body")) result.put("body", redact(result.path("body").asText(), sensitive));
      if (result.path("headers") instanceof ObjectNode returnedHeaders) {
        returnedHeaders.fields().forEachRemaining(header -> header.setValue(TextNode.valueOf(redact(header.getValue().asText(), sensitive))));
      }
    }
    safeUrl=safeUrl.replaceAll("(?i)([?&](?:[^=&]*(?:token|secret|password|api[_-]?key)[^=&]*)=)[^&]*","$1REDACTED");
    result.put("url", safeUrl);
    var history = result.deepCopy();
    history.remove("body");
    history.remove("headers");
    store.save("history", history, actor);
    return result;
  }
  private static String redact(String value, Set<String> secrets) {
    for (String secret : secrets) {
      String encoded = java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8);
      String quoted = TextNode.valueOf(secret).toString();
      value = value.replace(secret, "REDACTED").replace(encoded, "REDACTED")
        .replace(encoded.replace("+", "%20"), "REDACTED")
        .replace(quoted.substring(1, quoted.length() - 1), "REDACTED");
    }
    return value;
  }
}
