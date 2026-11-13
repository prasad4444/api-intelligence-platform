package com.apiintel.integrations;

import com.apiintel.network.NetworkGuard;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dynamic outbound host policy. Optional bootstrap lists may come from
 * INTEGRATION_HOSTS / INTEGRATION_PRIVATE_HOSTS. Saving a connector definition or
 * connecting a personal account automatically approves that base URL host, so
 * operators do not edit properties for each new integration.
 */
@Component
public class ApprovedHosts {

  private final RecordStore store;
  private final NetworkGuard guard;
  private final Set<String> bootstrap;
  private final Set<String> bootstrapPrivate;
  private final Set<String> dynamic = ConcurrentHashMap.newKeySet();
  private final Set<String> dynamicPrivate = ConcurrentHashMap.newKeySet();
  private final String friday;

  public ApprovedHosts(
    RecordStore store,
    NetworkGuard guard,
    @Value("${platform.integration-hosts:}") String hosts,
    @Value("${platform.integration-private-hosts:}") String privateHosts,
    @Value("${platform.friday-url:http://localhost:18080}") String friday
  ) {
    this.store = store;
    this.guard = guard;
    this.bootstrap = split(hosts);
    this.bootstrapPrivate = split(privateHosts);
    this.friday = friday;
  }

  public void approve(String baseUrl) {
    URI uri = URI.create(baseUrl);
    if (uri.getHost() == null) throw new IllegalArgumentException("Invalid base URL");
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    dynamic.add(host);
    if (looksPrivate(uri)) dynamicPrivate.add(host);
  }

  public boolean allows(String host) {
    if (host == null || host.isBlank()) return false;
    String normalized = host.toLowerCase(Locale.ROOT);
    if (known().contains(normalized)) return true;
    return refresh().contains(normalized);
  }

  public boolean privateApproved(String host) {
    String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
    if (bootstrapPrivate.contains(normalized) || dynamicPrivate.contains(normalized)) return true;
    refresh();
    return bootstrapPrivate.contains(normalized) || dynamicPrivate.contains(normalized);
  }

  public URI join(ObjectNode config, String path) {
    String base = config.path("baseUrl").asText();
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
    URI uri = URI.create(base.replaceAll("/$", "") + path);
    if (
      !Objects.equals(root.getHost(), uri.getHost()) ||
      root.getPort() != uri.getPort()
    ) throw new IllegalArgumentException(
      "Endpoint must stay on the configured host"
    );
    return uri;
  }

  public URI validateConnectorBase(ObjectNode config, String path) {
    String base = config.path("baseUrl").asText();
    URI root = URI.create(base);
    boolean localFriday =
      config.path("provider").asText().equals("friday") && base.equals(friday);
    if (!localFriday) {
      approve(base);
      String host = root.getHost().toLowerCase(Locale.ROOT);
      if (privateApproved(host) || looksPrivate(root)) {
        try {
          for (var address : InetAddress.getAllByName(root.getHost())) {
            boolean privateUnicast = isPrivateUnicast(address);
            if (!privateApproved(host) || !privateUnicast) guard.check(address);
          }
        } catch (UnknownHostException e) {
          throw new IllegalArgumentException("Host cannot be resolved");
        }
        dynamicPrivate.add(host);
      } else {
        guard.validate(base);
      }
    } else if (!Set.of("http", "https").contains(root.getScheme())) {
      throw new IllegalArgumentException("Invalid Friday scheme");
    }
    return join(config, path);
  }

  public void checkConnectedAddress(String baseUrl, InetAddress address) {
    URI root = URI.create(baseUrl);
    String host = root.getHost() == null ? "" : root.getHost().toLowerCase(Locale.ROOT);
    boolean privateUnicast = isPrivateUnicast(address);
    if (!privateApproved(host) || !privateUnicast) guard.check(address);
  }

  public void enforce(String url, ObjectNode connection) {
    URI uri = URI.create(url);
    if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("Use an HTTP(S) URL without embedded credentials");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    String site = connection.path("siteHost").asText("").toLowerCase(Locale.ROOT);
    String base = connection.path("baseUrl").asText("");
    if (!base.isBlank()) approve(base);
    if (!(host.equals(site) || allows(host))) {
      throw new IllegalArgumentException("Host must match the connection site or an approved connector host");
    }
    if (privateApproved(host) || looksPrivate(uri) || host.equals(site)) return;
    guard.validate(uri.toString());
  }

  private Set<String> known() {
    Set<String> all = new HashSet<>(bootstrap);
    all.addAll(bootstrapPrivate);
    all.addAll(dynamic);
    all.addAll(dynamicPrivate);
    return all;
  }

  private Set<String> refresh() {
    Set<String> all = known();
    for (String kind : List.of("integrations", "connections")) {
      for (ObjectNode row : store.list(kind)) {
        String base = row.path("baseUrl").asText("");
        if (base.isBlank()) continue;
        try {
          URI uri = URI.create(base);
          if (uri.getHost() == null) continue;
          String host = uri.getHost().toLowerCase(Locale.ROOT);
          dynamic.add(host);
          all.add(host);
          if (looksPrivate(uri)) {
            dynamicPrivate.add(host);
          }
        } catch (Exception ignored) {
        }
      }
    }
    return all;
  }

  private boolean looksPrivate(URI uri) {
    try {
      for (var address : InetAddress.getAllByName(uri.getHost())) {
        if (isPrivateUnicast(address)) return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  private boolean isPrivateUnicast(InetAddress address) {
    byte[] bytes = address.getAddress();
    return bytes.length == 4 && address.isSiteLocalAddress()
      || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
  }

  private Set<String> split(String value) {
    Set<String> out = ConcurrentHashMap.newKeySet();
    if (value == null) return out;
    for (String part : value.split(",")) if (!part.isBlank()) out.add(part.trim().toLowerCase(Locale.ROOT));
    return out;
  }
}
