package com.apiintel.network;

import java.net.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NetworkGuard {

  public URI validate(String url) {
    try {
      URI uri = URI.create(url);
      if (
        !Set.of("http", "https").contains(uri.getScheme()) ||
        uri.getHost() == null ||
        uri.getUserInfo() != null ||
        uri.getFragment() != null
      ) throw new IllegalArgumentException(
        "Use an HTTP(S) URL without credentials or fragments"
      );
      for (var a : InetAddress.getAllByName(uri.getHost())) check(a);
      return uri;
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Host cannot be resolved");
    }
  }

  public void check(InetAddress a) {
    byte[] b = a.getAddress();
    boolean special =
      b.length == 4 &&
      ((b[0] & 255) == 0 ||
        ((b[0] & 255) == 100 && (b[1] & 255) >= 64 && (b[1] & 255) <= 127) ||
        (b[0] & 255) >= 224 ||
        ((b[0] & 255) == 198 && ((b[1] & 255) == 18 || (b[1] & 255) == 19)));
    boolean v6 =
      b.length == 16 &&
      ((b[0] & 254) == 252 || ((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02));
    if (
      a.isAnyLocalAddress() ||
      a.isLoopbackAddress() ||
      a.isLinkLocalAddress() ||
      a.isSiteLocalAddress() ||
      a.isMulticastAddress() ||
      special ||
      v6
    ) throw new IllegalArgumentException(
      "Private, local and reserved network targets are blocked"
    );
  }
}
