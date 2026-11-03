package com.apiintel.network;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Service
public class RequestService {

  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private com.apiintel.environments.EnvironmentService environments;
  private final NetworkGuard guard;
  private final RecordStore store;

  public RequestService(NetworkGuard guard, RecordStore store) {
    this.guard = guard;
    this.store = store;
  }

  public ObjectNode send(ObjectNode input, String actor) {
    var sensitive = new java.util.HashSet<String>();
    if (environments != null) { var resolved=environments.resolve(input);input=resolved.request();sensitive.addAll(resolved.secrets()); }
    final ObjectNode payload=input;
    String method = input.path("method").asText("GET").toUpperCase();
    if (
      !Set.of(
        "GET",
        "POST",
        "PUT",
        "PATCH",
        "DELETE",
        "OPTIONS",
        "HEAD"
      ).contains(method)
    ) throw new IllegalArgumentException("Unsupported HTTP method");
    URI uri = guard.validate(input.path("url").asText());
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
    var req = web
      .method(HttpMethod.valueOf(method))
      .uri(uri)
      .headers(h ->
        payload
          .path("headers")
          .fields()
          .forEachRemaining(e -> {
            if (
              !Set.of(
                "host",
                "connection",
                "content-length",
                "transfer-encoding",
                "cookie",
                "proxy-authorization"
              ).contains(e.getKey().toLowerCase())
            ) h.set(e.getKey(), e.getValue().asText());
          })
      );
    long start = System.nanoTime();
    var result = store.parse("{}");
    try {
      var response = req
        .bodyValue(input.path("body").asText(""))
        .exchangeToMono(r ->
          r
            .bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(b -> {
              var out = store.parse("{}");
              out.put("status", r.statusCode().value());
              out.put("body", b);
              var hs = out.putObject("headers");
              r.headers()
                .asHttpHeaders()
                .forEach((k, v) -> hs.put(k, String.join(", ", v)));
              return out;
            })
        )
        .block(Duration.ofSeconds(20));
      result.setAll(response);
    } catch (Exception e) {
      result.put("status", 0);
      result.put(
        "error",
        "Request failed or response exceeded 2 MB / 20 seconds"
      );
    }
    result.put("durationMs", (System.nanoTime() - start) / 1_000_000);
    result.put("method", method);
    String safeUrl=uri.toString();
    for(String secret:sensitive) {safeUrl=safeUrl.replace(secret,"REDACTED").replace(java.net.URLEncoder.encode(secret,java.nio.charset.StandardCharsets.UTF_8),"REDACTED").replace(java.net.URLEncoder.encode(secret,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20"),"REDACTED");}
    safeUrl=safeUrl.replaceAll("(?i)([?&](?:[^=&]*(?:token|secret|password|api[_-]?key)[^=&]*)=)[^&]*","$1REDACTED");
    result.put("url", safeUrl);
    var history = result.deepCopy();
    history.remove("body");
    history.remove("headers");
    store.save("history", history, actor);
    return result;
  }
}
