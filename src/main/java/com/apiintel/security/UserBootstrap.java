package com.apiintel.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class UserBootstrap implements ApplicationRunner {

  private final PlatformUserService users;
  private final String admin;
  private final String developer;
  private final String reviewer;

  public UserBootstrap(
    PlatformUserService users,
    @Value("${platform.admin-password}") String admin,
    @Value("${platform.developer-password:}") String developer,
    @Value("${platform.reviewer-password:}") String reviewer
  ) {
    this.users = users;
    this.admin = admin;
    this.developer = developer;
    this.reviewer = reviewer;
  }

  @Override
  public void run(ApplicationArguments args) {
    users.bootstrap(admin, developer, reviewer);
  }
}
