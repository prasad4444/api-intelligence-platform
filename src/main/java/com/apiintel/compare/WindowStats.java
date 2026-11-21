package com.apiintel.compare;

import java.util.List;
import java.util.Map;

/**
 * Raw per-window statistics collected from Splunk. {@code available=false}
 * marks a failed/unavailable search (MISSING), which is never the same as a
 * measured zero. Percentiles are always derived from {@code durations} raw
 * values — never by averaging precomputed percentiles.
 */
public record WindowStats(
    boolean available,
    boolean partial,
    long count,
    long successes,
    long failures,
    List<Double> durations,
    Map<String, Long> exceptions,
    Map<String, Long> services,
    Map<String, Long> transactionTypes,
    Long latestTime,
    String error) {

  public static WindowStats missing(String error) {
    return new WindowStats(false, false, 0, 0, 0, List.of(), Map.of(), Map.of(), Map.of(), null, error);
  }

  public static WindowStats zero(Long latestTime) {
    return new WindowStats(true, false, 0, 0, 0, List.of(), Map.of(), Map.of(), Map.of(), latestTime, null);
  }

  public static WindowStats of(long count, long successes, long failures,
                               List<Double> durations, Long latestTime) {
    return new WindowStats(true, false, count, successes, failures, durations,
        Map.of(), Map.of(), Map.of(), latestTime, null);
  }

  public WindowStats markPartial() {
    return new WindowStats(available, true, count, successes, failures, durations,
        exceptions, services, transactionTypes, latestTime, error);
  }

  public double errorRate() {
    return count == 0 ? 0.0 : (double) failures / count;
  }
}
