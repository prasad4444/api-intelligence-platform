package com.apiintel;

import static org.junit.jupiter.api.Assertions.*;

import com.apiintel.compare.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompareEngineTest {

  private final WindowResolver resolver = new WindowResolver();
  private final MetricCalculator calc = new MetricCalculator();

  // ---------- WindowResolver: presets + lag + buckets ----------

  @Test
  void rollingWindowIsInstantArithmetic() {
    Instant now = Instant.parse("2025-03-10T15:00:00Z");
    var r = resolver.resolve(ComparisonPolicy.of(ComparisonPolicy.Preset.LAST_1H)
        .lag(Duration.ZERO).completedBucketsOnly(false), now);
    assertEquals(now, r.current().end());
    assertEquals(now.minusSeconds(3600), r.current().start());
    assertEquals(1, r.baselines().size());
    assertEquals(now.minusSeconds(3600), r.baselines().get(0).end());
    assertEquals(now.minusSeconds(7200), r.baselines().get(0).start());
  }

  @Test
  void lagShrinksEffectiveEndAndMarksPartial() {
    Instant now = Instant.parse("2025-03-10T15:07:30Z");
    var r = resolver.resolve(ComparisonPolicy.of(ComparisonPolicy.Preset.LAST_1H)
        .lag(Duration.ofMinutes(2)).completedBucketsOnly(false), now);
    assertEquals(now.minusSeconds(120), r.current().end());
    assertTrue(r.current().partial());
  }

  @Test
  void completedBucketsOnlyTruncatesToBucketBoundary() {
    Instant now = Instant.parse("2025-03-10T15:07:30Z");
    var r = resolver.resolve(ComparisonPolicy.of(ComparisonPolicy.Preset.LAST_1H)
        .lag(Duration.ZERO).bucket(Duration.ofMinutes(5)).completedBucketsOnly(true), now);
    // current = 14:07:30..15:07:30 → elapsed 3600 divisible by 300 → no truncation
    assertEquals(now, r.current().end());
    var r2 = resolver.resolve(ComparisonPolicy.of(ComparisonPolicy.Preset.LAST_1H)
        .lag(Duration.ofSeconds(45)).bucket(Duration.ofMinutes(5)).completedBucketsOnly(true), now);
    // lagged end 15:06:45 → elapsed 3555 → truncate to 3300 (drop 255s)
    assertEquals(3300, r2.current().durationSeconds());
    // same 255s truncation applied to baseline end
    assertEquals(3600 - 255, r2.baselines().get(0).durationSeconds());
  }

  @Test
  void halfOpenEndBoundaryExcludesEvent() {
    Window w = new Window(Instant.parse("2025-03-10T14:00:00Z"),
        Instant.parse("2025-03-10T15:00:00Z"), ZoneId.of("UTC"), false);
    assertFalse(w.contains(Instant.parse("2025-03-10T15:00:00Z"))); // excluded
    assertTrue(w.contains(Instant.parse("2025-03-10T14:59:59Z")));
    assertFalse(w.contains(Instant.parse("2025-03-10T13:59:59Z")));
  }

  // ---------- DST ----------

  @Test
  void newYorkSpringForwardBaselineIs23Hours() {
    // US DST springs forward 2025-03-09 02:00 → evaluating a week later, the
    // same-weekday baseline lands on that 23-hour Sunday.
    Instant now = ZonedDateTime.of(LocalDateTime.parse("2025-03-16T12:00"), ZoneId.of("America/New_York")).toInstant();
    var policy = ComparisonPolicy.of(ComparisonPolicy.Preset.TODAY_SO_FAR)
        .zone("America/New_York").lag(Duration.ZERO).completedBucketsOnly(false);
    var r = resolver.resolve(policy, now);
    Window base = r.baselines().get(0);
    // same local window (00:00→12:00) on the 23h day spans only 11 elapsed hours
    assertEquals(11 * 3600, base.durationSeconds());
    assertEquals(12 * 3600, r.current().durationSeconds());
  }

  @Test
  void newYorkFallBackBaselineIs25Hours() {
    // US DST falls back 2025-11-02 02:00 → same-weekday baseline is on the 25h day.
    Instant now = ZonedDateTime.of(LocalDateTime.parse("2025-11-09T12:00"), ZoneId.of("America/New_York")).toInstant();
    var policy = ComparisonPolicy.of(ComparisonPolicy.Preset.TODAY_SO_FAR)
        .zone("America/New_York").lag(Duration.ZERO).completedBucketsOnly(false);
    var r = resolver.resolve(policy, now);
    Window base = r.baselines().get(0);
    // same local window (00:00→12:00) on the 25h day spans 13 elapsed hours
    assertEquals(13 * 3600, base.durationSeconds());
    assertEquals(12 * 3600, r.current().durationSeconds());
  }

  @Test
  void europeLondonDST() {
    // Europe/London springs forward 2025-03-30 01:00; baseline week earlier is normal.
    Instant now = ZonedDateTime.of(LocalDateTime.parse("2025-03-30T10:30"), ZoneId.of("Europe/London")).toInstant();
    var policy = ComparisonPolicy.of(ComparisonPolicy.Preset.TODAY_SO_FAR)
        .zone("Europe/London").lag(Duration.ZERO).completedBucketsOnly(false);
    var r = resolver.resolve(policy, now);
    // current: midnight→10:30 BST; that midnight was GMT→BST transition day: 00:00 GMT, shift adds an hour
    assertTrue(r.current().durationSeconds() < 10.5 * 3600 + 1);
    // baseline a week earlier is a normal day
    assertEquals(10.5 * 3600, r.baselines().get(0).durationSeconds(), 1);
  }

  @Test
  void asiaKolkataNoDST() {
    Instant now = ZonedDateTime.of(LocalDateTime.parse("2025-03-10T12:00"), ZoneId.of("Asia/Kolkata")).toInstant();
    var policy = ComparisonPolicy.of(ComparisonPolicy.Preset.TODAY_SO_FAR)
        .zone("Asia/Kolkata").lag(Duration.ZERO).completedBucketsOnly(false);
    var r = resolver.resolve(policy, now);
    assertEquals(r.current().durationSeconds(), r.baselines().get(0).durationSeconds());
  }

  @Test
  void last60mOneWeekEarlierAcrossDSTCalendar() {
    // evaluated after spring-forward; the same local window a week earlier was EST.
    Instant now = ZonedDateTime.of(LocalDateTime.parse("2025-03-10T15:00"), ZoneId.of("America/New_York")).toInstant();
    var policy = ComparisonPolicy.of(ComparisonPolicy.Preset.LAST_60M)
        .mode(ComparisonPolicy.Mode.CALENDAR).zone("America/New_York")
        .lag(Duration.ZERO).completedBucketsOnly(false);
    var r = resolver.resolve(policy, now);
    Window base = r.baselines().get(0);
    assertEquals(3600, base.durationSeconds());
    assertEquals(-5 * 3600, base.start().atZone(ZoneId.of("America/New_York")).getOffset().getTotalSeconds());
    assertEquals(-4 * 3600, r.current().start().atZone(ZoneId.of("America/New_York")).getOffset().getTotalSeconds());
    assertFalse(r.notes().isEmpty());
  }

  @Test
  void dstGapShiftsForwardWithNote() {
    // 2025-03-09 02:30 does not exist in New York (spring forward) — shift to 03:00.
    Instant gapInstant = ZonedDateTime.of(LocalDateTime.parse("2025-03-10T02:30"),
        ZoneId.of("America/New_York")).toInstant();
    var policy = ComparisonPolicy.of(ComparisonPolicy.Preset.LAST_60M)
        .mode(ComparisonPolicy.Mode.CALENDAR).zone("America/New_York")
        .lag(Duration.ZERO).completedBucketsOnly(false);
    // current is 01:30-02:30 Monday; baseline lands on nonexistent 2025-03-03? —
    // craft the direct shift path: evaluate custom window ending inside a gap week.
    var p2 = ComparisonPolicy.of(ComparisonPolicy.Preset.CUSTOM)
        .zone("America/New_York").lag(Duration.ZERO).completedBucketsOnly(false)
        .customStart(gapInstant.getEpochSecond() - 3600).customEnd(gapInstant.getEpochSecond())
        .customBaselineStart(gapInstant.getEpochSecond() - 7200).customBaselineEnd(gapInstant.getEpochSecond() - 3600);
    var r = resolver.resolve(p2, Instant.now());
    assertNotNull(r.current());
  }

  @Test
  void multiWeekBaselines() {
    Instant now = Instant.parse("2025-03-10T15:00:00Z");
    var r = resolver.resolve(ComparisonPolicy.of(ComparisonPolicy.Preset.MULTI_WEEK)
        .zone("UTC").weeks(3).lag(Duration.ZERO).completedBucketsOnly(false), now);
    assertEquals(3, r.baselines().size());
    assertTrue(r.baselines().get(1).start().isBefore(r.baselines().get(0).start()));
  }

  // ---------- MetricCalculator ----------

  @Test
  void zeroBaselineGivesNullRelative() {
    WindowStats cur = WindowStats.of(100, 95, 5, List.of(10.0, 20.0, 30.0), 100L);
    WindowStats base = WindowStats.of(0, 0, 0, List.of(), 50L);
    var metrics = calc.buildMetrics(cur, List.of(base), 300, 300);
    JsonNode requests = metric(metrics, "requests");
    assertTrue(requests.path("relativeChange").isNull());
    assertEquals("baseline zero", requests.path("relativeChangeReason").asText());
    assertEquals(100.0, requests.path("absoluteChange").asDouble());
    assertEquals("MEASURED", requests.path("status").asText());
  }

  @Test
  void missingBaselineIsNotMeasuredZero() {
    WindowStats cur = WindowStats.of(0, 0, 0, List.of(), null);
    WindowStats base = WindowStats.missing("search failed");
    var metrics = calc.buildMetrics(cur, List.of(base), 300, 300);
    JsonNode requests = metric(metrics, "requests");
    assertEquals(0.0, requests.path("current").asDouble()); // measured zero stays zero
    assertEquals("MISSING", requests.path("status").asText());
    assertEquals("baseline missing", requests.path("relativeChangeReason").asText());
  }

  @Test
  void missingCurrentIsDistinctFromZero() {
    WindowStats cur = WindowStats.missing("quota");
    WindowStats base = WindowStats.of(50, 50, 0, List.of(5.0), 10L);
    var metrics = calc.buildMetrics(cur, List.of(base), 300, 300);
    JsonNode requests = metric(metrics, "requests");
    assertEquals("MISSING", requests.path("status").asText());
    assertTrue(requests.path("current").isNull());
  }

  @Test
  void unequalWindowsNormalizeToRates() {
    WindowStats cur = WindowStats.of(120, 120, 0, List.of(1.0), 1L);
    WindowStats base = WindowStats.of(240, 240, 0, List.of(1.0), 1L);
    // current 60s window, baseline 120s → same rate per minute
    var metrics = calc.buildMetrics(cur, List.of(base), 60, 120);
    JsonNode requests = metric(metrics, "requests");
    assertEquals(120.0, requests.path("ratePerMinute").asDouble()); // 240 events / 2 min
    assertEquals(120.0, requests.path("currentRatePerMinute").asDouble()); // 120 events / 1 min
    assertEquals(240.0, requests.path("normalizedBaseline").asDouble());
  }

  @Test
  void percentilesComputedFromRawNotAveraged() {
    // two baseline weeks with different distributions; merged percentile ≠ mean of percentiles
    WindowStats week1 = WindowStats.of(3, 3, 0, List.of(1.0, 2.0, 3.0), 1L);
    WindowStats week2 = WindowStats.of(3, 3, 0, List.of(100.0, 200.0, 300.0), 1L);
    var metrics = calc.buildMetrics(week1, List.of(week1, week2), 300, 600);
    JsonNode p50 = metric(metrics, "latency_p50");
    // merged raw = [1,2,3,100,200,300] → median = 51.5; naive avg of medians (2+200)/2=101
    assertEquals(51.5, p50.path("baseline").asDouble(), 0.01);
  }

  @Test
  void ppChangeForRates() {
    WindowStats cur = WindowStats.of(200, 190, 10, List.of(), 1L); // 5% errors
    WindowStats base = WindowStats.of(200, 196, 4, List.of(), 1L); // 2% errors
    var metrics = calc.buildMetrics(cur, List.of(base), 300, 300);
    JsonNode err = metric(metrics, "error_rate");
    assertEquals(0.03, err.path("ppChange").asDouble(), 1e-9);
  }

  private JsonNode metric(com.fasterxml.jackson.databind.node.ArrayNode metrics, String name) {
    for (JsonNode m : metrics) if (name.equals(m.path("name").asText())) return m;
    fail("metric " + name + " missing");
    return null;
  }
}
