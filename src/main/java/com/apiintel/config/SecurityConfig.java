package com.apiintel.config;

import com.apiintel.diagnostics.ApiRequestLog;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http
      .authorizeHttpRequests(a ->
        a
          .requestMatchers(
            "/",
            "/index.html",
            "/assets/**",
            "/api/csrf",
            "/error"
          )
          .permitAll()
          .anyRequest()
          .authenticated()
      )
      .csrf(c ->
        c
          .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
          .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
      )
      .formLogin(f ->
        f
          .loginProcessingUrl("/api/login")
          .successHandler((q, s, a) -> s.setStatus(204))
          .failureHandler((q, s, e) -> {
            ApiRequestLog.securityFailure(q, s, "login_rejected");
            s.sendError(401);
          })
      )
      .logout(l ->
        l
          .logoutUrl("/api/logout")
          .logoutSuccessHandler((q, s, a) -> s.setStatus(204))
      )
      .exceptionHandling(e ->
        e.authenticationEntryPoint((q, s, x) -> {
          ApiRequestLog.securityFailure(q, s, "platform_session_required");
          s.sendError(401);
        }).accessDeniedHandler((q, s, x) -> {
          String reason = x instanceof MissingCsrfTokenException ? "csrf_missing"
              : x instanceof InvalidCsrfTokenException ? "csrf_invalid" : "platform_access_denied";
          ApiRequestLog.securityFailure(q, s, reason);
          s.sendError(403);
        })
      )
      .headers(h ->
        h.contentSecurityPolicy(c ->
          c.policyDirectives(
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
          )
        )
      )
      .build();
  }
}
