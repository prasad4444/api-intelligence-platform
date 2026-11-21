package com.apiintel.compare;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns raw per-window stats into comparable metrics: current vs baseline
 * absolute/relative change, pp-change for rates, and rate-per-minute
 * normalization so unequal windows (e.g. a 23h DST baseline vs a 24h current)
 * compare fairly. Percentiles are computed from raw duration values only.
 */
public class MetricCalculator {

  private static final JsonNodeFactory J = JsonNodeFactory.instance;

  /** One current window against the union (or single) baseline stats. */
  public ArrayNode buildMetrics(WindowStats current, List<WindowStats> baselines,
                                long currentSeconds, long baselineSeconds) {
    WindowStats baseline = merge(baselines);
    ArrayNode out = J.arrayNode();
    out.add(metric("requests", "count", current.available() ? (double) current.count() : null,
        baseline.available() ? (double) baseline.count() : null,
        current, baseline, currentSeconds, baselineSeconds));
    out.add(metric("errors", "count", current.available() ? (double) current.failures() : null,
        baseline.available() ? (double) baseline.failures() : null,
        current, baseline, currentSeconds, baselineSeconds));
    double curErr = current.available() && current.count() > 0 ? current.errorRate() : Double.NaN;
    double baseErr = baseline.available() && baseline.count() > 0 ? baseline.errorRate() : Double.NaN;
    out.add(rateMetric("error_rate", "fraction", curErr, baseErr, current, baseline));
    for (double p : new double[]{50, 95, 99}) {
      Double c = current.available() ? percentile(current.durations(), p) : null;
      Double b = baseline.available() ? percentile(baseline.durations(), p) : null;
      out.add(metric("latency_p" + (int) p, "ms", c, b, current, baseline,
          currentSeconds, baselineSeconds));
    }
    return out;
  }

  private ObjectNode metric(String name, String unit, Double current, Double baseline,
                            WindowStats cur, WindowStats base,
                            long currentSeconds, long baselineSeconds) {
    ObjectNode m = base(name, unit, current, baseline, cur, base);
    if (current != null && baseline != null) {
      double rate = baselineSeconds > 0 ? baseline / (baselineSeconds / 60.0) : 0;
      m.put("ratePerMinute", rate);
      if (currentSeconds != baselineSeconds && currentSeconds > 0) {
        double curRate = current / (currentSeconds / 60.0);
        m.put("currentRatePerMinute", curRate);
        m.put("normalizedBaseline", curRate * (baselineSeconds / 60.0));
      }
    }
    return m;
  }

  private ObjectNode rateMetric(String name, String unit, double current, double baseline,
                                WindowStats cur, WindowStats base) {
    Double c = Double.isNaN(current) ? null : current;
    Double b = Double.isNaN(baseline) ? null : baseline;
    ObjectNode m = base(name, unit, c, b, cur, base);
    if (c != null && b != null) m.put("ppChange", c - b);
    return m;
  }

  private ObjectNode base(String name, String unit, Double current, Double baseline,
                          WindowStats cur, WindowStats base) {
    ObjectNode m = J.objectNode();
    m.put("name", name);
    m.put("unit", unit);
    String status;
    if (!cur.available() || (base != null && !base.available())) {
      status = "MISSING";
    } else if (cur.partial()) {
      status = "PARTIAL";
    } else {
      status = "MEASURED";
    }
    if (current == null) m.putNull("current"); else m.put("current", current);
    if (baseline == null) m.putNull("baseline"); else m.put("baseline", baseline);
    if (current != null && baseline != null) {
      m.put("absoluteChange", current - baseline);
      if (baseline == 0) {
        m.putNull("relativeChange");
        m.put("relativeChangeReason", "baseline zero");
      } else {
        m.put("relativeChange", (current - baseline) / baseline);
      }
    } else {
      m.putNull("absoluteChange");
      m.putNull("relativeChange");
      if (baseline == null && base != null && base.available()) {
        m.put("relativeChangeReason", "baseline zero");
      } else {
        m.put("relativeChangeReason", "baseline missing");
      }
    }
    m.put("status", status);
    return m;
  }

  /**
   * Merge several baseline windows (multi-week) into one by concatenating raw
   * durations and summing counts — percentiles stay honest because they are
   * computed over the merged raw sample, not averaged per window.
   */
  public WindowStats merge(List<WindowStats> windows) {
    if (windows == null || windows.isEmpty()) return WindowStats.missing("no baseline");
    if (windows.size() == 1) return windows.get(0);
    long count = 0, ok = 0, fail = 0;
    boolean available = false;
    List<Double> durations = new ArrayList<>();
    var exceptions = new java.util.HashMap<String, Long>();
    var services = new java.util.HashMap<String, Long>();
    var txns = new java.util.HashMap<String, Long>();
    Long latest = null;
    for (WindowStats w : windows) {
      if (!w.available()) continue;
      available = true;
      count += w.count(); ok += w.successes(); fail += w.failures();
      durations.addAll(w.durations());
      w.exceptions().forEach((k, v) -> exceptions.merge(k, v, Long::sum));
      w.services().forEach((k, v) -> services.merge(k, v, Long::sum));
      w.transactionTypes().forEach((k, v) -> txns.merge(k, v, Long::sum));
      if (w.latestTime() != null && (latest == null || w.latestTime() > latest)) latest = w.latestTime();
    }
    if (!available) return WindowStats.missing("no baseline");
    boolean partial = windows.stream().anyMatch(WindowStats::partial);
    return new WindowStats(true, partial, count, ok, fail, durations, exceptions, services, txns, latest, null);
  }

  /** Percentile via linear interpolation over sorted raw values (index p/100*(n-1)). */
  public static Double percentile(List<Double> values, double p) {
    if (values == null || values.isEmpty()) return null;
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    if (sorted.size() == 1) return sorted.get(0);
    double rank = p / 100.0 * (sorted.size() - 1);
    int low = (int) Math.floor(rank);
    int high = (int) Math.ceil(rank);
    if (low == high) return sorted.get(low);
    double frac = rank - low;
    return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * frac;
  }
}
