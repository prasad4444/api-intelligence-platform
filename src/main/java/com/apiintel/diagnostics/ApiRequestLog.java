package com.apiintel.diagnostics;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiRequestLog extends OncePerRequestFilter {
  public static final String HEADER = "X-Request-ID";
  private static final String MDC_KEY = "apiRequestId";
  private static final String REASON = ApiRequestLog.class.getName() + ".reason";
  private static final Logger LOG = LoggerFactory.getLogger(ApiRequestLog.class);

  private final UsageStore usage;

  public ApiRequestLog(UsageStore usage) { this.usage = usage; }

  public static String requestId() {
    String id = MDC.get(MDC_KEY);
    return id == null ? "-" : id;
  }

  public static void securityFailure(HttpServletRequest request, HttpServletResponse response, String reason) {
    request.setAttribute(REASON, reason);
    response.setHeader("X-Platform-Failure", reason);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String route = request.getRequestURI().substring(request.getContextPath().length());
    return !route.startsWith("/api/") && !route.equals("/v3/api-docs");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, id);
    response.setHeader(HEADER, id);
    long started = System.nanoTime();
    boolean failed = false;
    String method = request.getMethod();
    if (!java.util.Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)) method = "OTHER";
    try {
      chain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException error) {
      failed = true;
      LOG.error("event=platform_request_exception requestId={} errorType={}", id, error.getClass().getSimpleName());
      throw error;
    } finally {
      String route = route(request);
      int status = failed ? 500 : response.getStatus();
      String reason = (String) request.getAttribute(REASON);
      var level = status >= 400 ? org.slf4j.event.Level.WARN
          : request.getMethod().equals("GET") ? org.slf4j.event.Level.DEBUG : org.slf4j.event.Level.INFO;
      long elapsed = OutboundLog.elapsed(started);
      LOG.atLevel(level).log("event=platform_request requestId={} method={} route={} status={} reason={} durationMs={} async={}",
          id, method, route, status, reason == null ? "-" : reason, elapsed, request.isAsyncStarted());
      if (!route.equals("/api/usage")) {
        usage.record(id, "platform", route, method, "", status, reason == null ? "-" : reason, elapsed);
      }
      if (previous == null) MDC.remove(MDC_KEY);
      else MDC.put(MDC_KEY, previous);
    }
  }

  private String route(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (pattern != null) return pattern.toString();
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.startsWith("/api/workspace/")) return "/api/workspace/**";
    if (path.startsWith("/api/integrations/")) return "/api/integrations/**";
    if (path.startsWith("/api/tests/")) return "/api/tests/**";
    return switch (path) {
      case "/api/login", "/api/logout", "/api/me", "/api/csrf", "/api/client/send", "/v3/api-docs" -> path;
      default -> "unmapped-api";
    };
  }
}
