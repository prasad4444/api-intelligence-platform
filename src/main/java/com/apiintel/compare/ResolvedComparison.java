package com.apiintel.compare;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** The frozen result of resolving a policy against one evaluation instant. */
public record ResolvedComparison(
    Instant evaluatedAt,
    Window current,
    List<Window> baselines,
    Duration bucket,
    Duration lag,
    List<String> notes) {}
