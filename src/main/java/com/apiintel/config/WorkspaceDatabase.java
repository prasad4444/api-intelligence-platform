package com.apiintel.config;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkspaceDatabase {
  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource dataSource(DataSourceProperties properties) throws IOException {
    var source = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    String url = properties.getUrl();
    if (sqlite(url)) {
      String file = url.substring("jdbc:sqlite:".length()).split("\\?", 2)[0];
      if (!file.isBlank() && !file.equals(":memory:") && !file.startsWith("file:")) {
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
      }
      // SQLite serializes writers. One pooled connection avoids write-lock contention
      // inside the application and keeps an in-memory test database alive.
      source.setMaximumPoolSize(1);
      source.setMinimumIdle(1);
      source.addDataSourceProperty("busy_timeout", "10000");
      source.addDataSourceProperty("journal_mode", "WAL");
    }
    return source;
  }

  @Bean
  FlywayConfigurationCustomizer workspaceSchema(DataSourceProperties properties) {
    return configuration -> configuration.locations(sqlite(properties.getUrl())
      ? "classpath:db/sqlite" : "classpath:db/migration");
  }

  private boolean sqlite(String url) { return url != null && url.startsWith("jdbc:sqlite:"); }
}
