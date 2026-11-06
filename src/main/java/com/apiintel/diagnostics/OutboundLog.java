package com.apiintel.diagnostics;

import java.net.*;
import java.nio.channels.UnresolvedAddressException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;

@org.springframework.stereotype.Component
public final class OutboundLog {
  private static final Logger LOG = LoggerFactory.getLogger(OutboundLog.class);
  private static final Set<String> SCHEMES = Set.of("bearer", "basic", "negotiate", "ntlm", "digest", "dpop");

  private final UsageStore usage;

  @org.springframework.beans.factory.annotation.Autowired
  public OutboundLog(UsageStore usage) { this.usage = usage; }

  // Service unit tests can run with console diagnostics alone.
  public OutboundLog() { this.usage = null; }

  public static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

  public static String scheme(String value) {
    if (value == null || value.isBlank()) return "none";
    String scheme = value.strip().split("[\\s,]", 2)[0].toLowerCase(Locale.ROOT);
    return SCHEMES.contains(scheme) ? scheme : "custom";
  }

  public static String challenge(HttpHeaders headers) {
    if (headers == null) return "none";
    var values = headers.get(HttpHeaders.WWW_AUTHENTICATE);
    if (values == null) return "none";
    var names = new LinkedHashSet<String>();
    for (String value : values) names.add(scheme(value));
    return String.join(",", names);
  }

  public static String challengeError(HttpHeaders headers) {
    if (headers == null || headers.get(HttpHeaders.WWW_AUTHENTICATE) == null) return "none";
    var error = java.util.regex.Pattern.compile("(?i)\\berror\\s*=\\s*\"?(invalid_token|insufficient_scope|invalid_request)\\b");
    for (String value : headers.get(HttpHeaders.WWW_AUTHENTICATE)) {
      var match = error.matcher(value);
      if (match.find()) return match.group(1).toLowerCase(Locale.ROOT);
    }
    return "none";
  }

  public static boolean hasCredentialHeader(HttpHeaders headers) {
    return headers.keySet().stream().anyMatch(name -> name.matches("(?i).*(api[-_]?key|token|secret|subscription[-_]?key|credential).*")
      && headers.getFirst(name) != null && !headers.getFirst(name).isBlank());
  }

  public void start(String source, String method, URI uri, String auth, boolean credentialHeader, boolean profile, boolean cookieDropped) {
    LOG.info("event=upstream_start requestId={} source={} method={} host={} auth={} credentialHeader={} profile={} queryPresent={} cookieDropped={}",
        ApiRequestLog.requestId(), source, method, uri.getHost(), auth, credentialHeader, profile, uri.getRawQuery() != null, cookieDropped);
  }

  public void result(String id, String source, String method, URI uri, int status, String challenge, long start, String reason) {
    long duration = elapsed(start);
    if (usage != null && !id.equals("-")) usage.record(id, "upstream", source, method, uri.getHost(), status, reason, duration);
    LOG.atLevel(status == 0 || status >= 300 ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.INFO)
      .log("event=upstream_result requestId={} source={} method={} host={} status={} challenge={} reason={} durationMs={}",
        id, source, method, uri.getHost(), status, challenge, reason, duration);
  }

  public void rejected(String source, String stage, Throwable error) {
    String id = ApiRequestLog.requestId();
    String reason = failure(error);
    if (usage != null && !id.equals("-")) usage.record(id, "preflight", source, "", "", 0, stage + ":" + reason, 0);
    LOG.warn("event=upstream_not_sent requestId={} source={} stage={} reason={} errorType={}",
        id, source, stage, reason, error.getClass().getSimpleName());
  }

  public static String failure(Throwable error) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) return "dns";
      if (cause instanceof SSLException) return "tls";
      if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException
          || cause instanceof io.netty.handler.timeout.TimeoutException) return "timeout";
      if (cause instanceof ConnectException) return "connect";
      if (cause instanceof DataBufferLimitException) return "response_limit";
    }
    return error instanceof IllegalArgumentException ? "invalid_or_blocked" : "transport_error";
  }

  public static String statusReason(int status) {
    return switch (status) {
      case 401 -> "upstream_unauthorized";
      case 403 -> "upstream_forbidden";
      case 407 -> "proxy_auth_required";
      case 429 -> "rate_limited";
      default -> status >= 500 ? "upstream_error" : status >= 400 ? "upstream_rejected" : status >= 300 ? "redirect" : "completed";
    };
  }
}
