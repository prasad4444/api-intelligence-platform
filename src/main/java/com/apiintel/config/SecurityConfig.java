package com.apiintel.config;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
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
  UserDetailsService users(
    @Value("${platform.admin-password}") String admin,
    @Value("${platform.developer-password}") String dev,
    @Value("${platform.reviewer-password}") String review
  ) {
    if (admin.isBlank()) throw new IllegalStateException(
      "Set ADMIN_PASSWORD before starting the platform"
    );
    var encoder = new BCryptPasswordEncoder();
    var users = new ArrayList<UserDetails>();
    users.add(
      User.withUsername("admin")
        .password(encoder.encode(admin))
        .roles("ADMIN", "REVIEWER", "DEVELOPER", "TESTER")
        .build()
    );
    if (!dev.isBlank()) users.add(
      User.withUsername("developer")
        .password(encoder.encode(dev))
        .roles("DEVELOPER", "TESTER")
        .build()
    );
    if (!review.isBlank()) users.add(
      User.withUsername("reviewer")
        .password(encoder.encode(review))
        .roles("REVIEWER")
        .build()
    );
    return new InMemoryUserDetailsManager(users);
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
          .failureHandler((q, s, e) -> s.sendError(401))
      )
      .logout(l ->
        l
          .logoutUrl("/api/logout")
          .logoutSuccessHandler((q, s, a) -> s.setStatus(204))
      )
      .exceptionHandling(e ->
        e.authenticationEntryPoint((q, s, x) -> s.sendError(401))
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
