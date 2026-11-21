package com.apiintel.compare;

import java.time.Duration;
import java.time.ZoneId;

/** Describes how a report window and its baselines are resolved. */
public class ComparisonPolicy {

  public enum Preset {
    LAST_5M, LAST_15M, LAST_30M, LAST_1H, LAST_6H, LAST_24H,
    LAST_60M, TODAY_SO_FAR, CUSTOM, MULTI_WEEK
  }

  public enum Mode { ROLLING, CALENDAR }

  private Preset preset = Preset.LAST_1H;
  private Mode mode = Mode.ROLLING;
  private ZoneId zone = ZoneId.of("UTC");
  private Duration bucket = Duration.ofMinutes(5);
  private Duration lag = Duration.ofMinutes(2);
  private boolean completedBucketsOnly = true;
  private int weeks = 1;
  private Long customStart;
  private Long customEnd;
  private Long customBaselineStart;
  private Long customBaselineEnd;

  public Preset preset() { return preset; }
  public ComparisonPolicy preset(Preset p) { this.preset = p; return this; }
  public Mode mode() { return mode; }
  public ComparisonPolicy mode(Mode m) { this.mode = m; return this; }
  public ZoneId zone() { return zone; }
  public ComparisonPolicy zone(String z) { this.zone = ZoneId.of(z); return this; }
  public Duration bucket() { return bucket; }
  public ComparisonPolicy bucket(Duration d) { this.bucket = d; return this; }
  public Duration lag() { return lag; }
  public ComparisonPolicy lag(Duration d) { this.lag = d; return this; }
  public boolean completedBucketsOnly() { return completedBucketsOnly; }
  public ComparisonPolicy completedBucketsOnly(boolean v) { this.completedBucketsOnly = v; return this; }
  public int weeks() { return weeks; }
  public ComparisonPolicy weeks(int w) { this.weeks = Math.max(1, w); return this; }
  public Long customStart() { return customStart; }
  public ComparisonPolicy customStart(Long s) { this.customStart = s; return this; }
  public Long customEnd() { return customEnd; }
  public ComparisonPolicy customEnd(Long e) { this.customEnd = e; return this; }
  public Long customBaselineStart() { return customBaselineStart; }
  public ComparisonPolicy customBaselineStart(Long s) { this.customBaselineStart = s; return this; }
  public Long customBaselineEnd() { return customBaselineEnd; }
  public ComparisonPolicy customBaselineEnd(Long e) { this.customBaselineEnd = e; return this; }

  public static ComparisonPolicy of(Preset p) { return new ComparisonPolicy().preset(p); }
}
