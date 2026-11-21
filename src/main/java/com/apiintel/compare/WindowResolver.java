package com.apiintel.compare;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a {@link ComparisonPolicy} into concrete half-open instant windows.
 *
 * <p>ROLLING mode uses instant arithmetic (a week is always 7*24h of elapsed
 * time). CALENDAR mode subtracts weeks on the local date-time and re-resolves
 * via {@link ZonedDateTime#ofLocal}: on a DST overlap the earlier offset is
 * chosen, on a gap the local time shifts forward — either way a note records
 * the adjustment, and {@link Window#durationSeconds()} reflects the real
 * duration (e.g. 23h or 25h across a transition).
 *
 * <p>Lag shrinks every window's effective end to {@code min(end, evaluatedAt -
 * lag)}. With {@code completedBucketsOnly} the current window is truncated to
 * its last complete bucket boundary and the same truncation is applied to each
 * baseline so comparisons stay apples-to-apples; otherwise the final partial
 * bucket is marked via {@link Window#partial()}.
 */
public class WindowResolver {

  public ResolvedComparison resolve(ComparisonPolicy policy, Instant evaluatedAt) {
    List<String> notes = new ArrayList<>();
    ZoneId zone = policy.zone();
    List<Window> baselines = new ArrayList<>();
    Window current;

    switch (policy.preset()) {
      case LAST_5M -> { current = rolling(evaluatedAt, Duration.ofMinutes(5), zone);
        baselines.add(rolling(current.start(), Duration.ofMinutes(5), zone)); }
      case LAST_15M -> { current = rolling(evaluatedAt, Duration.ofMinutes(15), zone);
        baselines.add(rolling(current.start(), Duration.ofMinutes(15), zone)); }
      case LAST_30M -> { current = rolling(evaluatedAt, Duration.ofMinutes(30), zone);
        baselines.add(rolling(current.start(), Duration.ofMinutes(30), zone)); }
      case LAST_1H -> { current = rolling(evaluatedAt, Duration.ofHours(1), zone);
        baselines.add(rolling(current.start(), Duration.ofHours(1), zone)); }
      case LAST_6H -> { current = rolling(evaluatedAt, Duration.ofHours(6), zone);
        baselines.add(rolling(current.start(), Duration.ofHours(6), zone)); }
      case LAST_24H -> { current = rolling(evaluatedAt, Duration.ofHours(24), zone);
        baselines.add(rolling(current.start(), Duration.ofHours(24), zone)); }
      case LAST_60M -> {
        current = rolling(evaluatedAt, Duration.ofMinutes(60), zone);
        if (policy.mode() == ComparisonPolicy.Mode.CALENDAR) {
          baselines.add(calendarShift(current, 1, zone, notes));
        } else {
          baselines.add(new Window(current.start().minus(Duration.ofDays(7)),
              current.end().minus(Duration.ofDays(7)), zone, false));
        }
      }
      case TODAY_SO_FAR -> {
        ZonedDateTime localNow = evaluatedAt.atZone(zone);
        ZonedDateTime start = localNow.toLocalDate().atStartOfDay(zone);
        current = new Window(start.toInstant(), evaluatedAt, zone, false);
        baselines.add(calendarShift(current, 1, zone, notes));
      }
      case MULTI_WEEK -> {
        ZonedDateTime localNow = evaluatedAt.atZone(zone);
        ZonedDateTime start = localNow.toLocalDate().atStartOfDay(zone);
        current = new Window(start.toInstant(), evaluatedAt, zone, false);
        for (int w = 1; w <= policy.weeks(); w++) {
          baselines.add(calendarShift(current, w, zone, notes));
        }
      }
      case CUSTOM -> {
        if (policy.customStart() == null || policy.customEnd() == null
            || policy.customBaselineStart() == null || policy.customBaselineEnd() == null) {
          throw new IllegalArgumentException("CUSTOM requires customStart/customEnd/customBaselineStart/customBaselineEnd");
        }
        current = new Window(Instant.ofEpochSecond(policy.customStart()),
            Instant.ofEpochSecond(policy.customEnd()), zone, false);
        baselines.add(new Window(Instant.ofEpochSecond(policy.customBaselineStart()),
            Instant.ofEpochSecond(policy.customBaselineEnd()), zone, false));
      }
      default -> throw new IllegalArgumentException("Unsupported preset " + policy.preset());
    }

    Instant lagEnd = evaluatedAt.minus(policy.lag());
    boolean currentPartial = false;
    if (current.end().isAfter(lagEnd)) {
      currentPartial = true;
      current = new Window(current.start(), max(current.start(), lagEnd), zone, true);
    }
    List<Window> laggedBaselines = new ArrayList<>();
    for (Window b : baselines) {
      Instant baseEnd = b.end().isAfter(lagEnd) ? lagEnd : b.end();
      laggedBaselines.add(new Window(b.start(), max(b.start(), baseEnd), zone,
          b.end().isAfter(lagEnd) || b.partial()));
    }
    baselines = laggedBaselines;

    if (policy.completedBucketsOnly()) {
      long bucketS = Math.max(1, policy.bucket().getSeconds());
      long elapsed = current.durationSeconds();
      long complete = (elapsed / bucketS) * bucketS;
      if (complete < elapsed) {
        long dropped = elapsed - complete;
        Instant newEnd = current.start().plusSeconds(complete);
        current = new Window(current.start(), newEnd, zone, true);
        notes.add("Truncated last partial bucket (" + dropped + "s) for completed-buckets-only comparison");
        List<Window> trimmed = new ArrayList<>();
        for (Window b : baselines) {
          Instant trimmedEnd = b.end().minusSeconds(Math.min(dropped, b.durationSeconds()));
          trimmed.add(new Window(b.start(), trimmedEnd, zone, b.partial()));
        }
        baselines = trimmed;
      }
    } else {
      long bucketS = Math.max(1, policy.bucket().getSeconds());
      boolean partial = currentPartial || current.durationSeconds() % bucketS != 0;
      if (partial && !current.partial()) {
        current = new Window(current.start(), current.end(), zone, true);
      }
    }

    return new ResolvedComparison(evaluatedAt, current, List.copyOf(baselines),
        policy.bucket(), policy.lag(), List.copyOf(notes));
  }

  private Window rolling(Instant end, Duration length, ZoneId zone) {
    return new Window(end.minus(length), end, zone, false);
  }

  /**
   * Shift a window back {@code weeks} on the local calendar. Local date-times
   * are subtracted first, then re-resolved in the zone: gaps shift forward,
   * overlaps keep the earlier offset — matching Splunk users' mental model of
   * "same local window last week" across DST.
   */
  private Window calendarShift(Window w, int weeks, ZoneId zone, List<String> notes) {
    Instant start = shiftLocal(w.start(), zone, weeks, "start", notes);
    Instant end = shiftLocal(w.end(), zone, weeks, "end", notes);
    if (!end.isAfter(start)) end = start;
    return new Window(start, end, zone, false);
  }

  private Instant shiftLocal(Instant instant, ZoneId zone, int weeks, String label, List<String> notes) {
    ZonedDateTime original = instant.atZone(zone);
    LocalDateTime shifted = original.toLocalDateTime().minusWeeks(weeks);
    ZonedDateTime resolved = ZonedDateTime.ofLocal(shifted, zone, null);
    if (!resolved.toLocalDateTime().equals(shifted)) {
      notes.add("DST gap: " + label + " " + shifted + " shifted to " + resolved.toLocalDateTime()
          + " " + zone.getId());
    } else if (resolved.getOffset() != original.getOffset()
        && zone.getRules().isDaylightSavings(resolved.toInstant()) != zone.getRules().isDaylightSavings(instant)) {
      notes.add("DST transition between current and baseline at " + shifted + " " + zone.getId());
    }
    return resolved.toInstant();
  }

  private Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
