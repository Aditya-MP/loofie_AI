package com.loofie.core.creator.domain;

import java.time.LocalDate;

/**
 * One number on a passport with where it came from and when (Foundation v2: every metric stores its source and date).
 * API_VERIFIED values are read from Backend A (decision C2); the other two are stored here.
 */
public record MetricValue(Metric metric, long value, Source source, LocalDate capturedOn) {

    public enum Metric { FOLLOWERS, AVG_VIEWS_10 }

    public enum Source { API_VERIFIED, SELF_REPORTED, OPS_CHECKED }
}
