package com.apiintel.compare;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** A half-open [start, end) instant window with its local representation. */
public record Window(
    Instant start,
    Instant end,
    ZoneId zone,
    boolean partial) {

  public long durationSeconds() {
    return Math.max(0, end.getEpochSecond() - start.getEpochSecond());
  }

  public ZonedDateTime localStart() { return start.atZone(zone); }
  public ZonedDateTime localEnd() { return end.atZone(zone); }

  /** True when the instant falls inside [start, end) — the end boundary is excluded. */
  public boolean contains(Instant instant) {
    return !instant.isBefore(start) && instant.isBefore(end);
  }

  public Window withEnd(Instant newEnd, boolean partialFlag) {
    return new Window(start, newEnd, zone, partialFlag);
  }
}
