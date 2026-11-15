package com.loofie.core.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Outbox health as metrics, refreshed every 15s from one cheap query (partial index on unpublished
 * rows). Alert on {@code loofie.outbox.oldest.age.seconds}: if events stop flowing it climbs.
 * Values are cached so a metrics scrape never touches the database.
 */
@Component
public class OutboxMonitor {

    private static final Logger log = LoggerFactory.getLogger(OutboxMonitor.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong failing = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong parked = new AtomicLong();

    public OutboxMonitor(JdbcClient jdbc, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("loofie.outbox.backlog", backlog, AtomicLong::get)
                .description("Outbox rows not yet published").register(meters);
        Gauge.builder("loofie.outbox.failing", failing, AtomicLong::get)
                .description("Unpublished outbox rows that have failed at least once").register(meters);
        Gauge.builder("loofie.outbox.oldest.age.seconds", oldestAgeSeconds, AtomicLong::get)
                .description("Age of the oldest unpublished outbox row").baseUnit("seconds").register(meters);
        // Alert on > 0: a parked row is an event the receiver refused for good (a mapping or contract bug).
        Gauge.builder("loofie.outbox.parked", parked, AtomicLong::get)
                .description("Outbox rows the receiver rejected, waiting for a fix and requeue").register(meters);
    }

    @Scheduled(fixedDelayString = "${loofie.outbox.monitor-interval:15s}")
    public void refresh() {
        try {
            var stats = jdbc.sql("""
                    SELECT count(*) AS backlog, count(*) FILTER (WHERE attempts > 0) AS failing, min(created_at) AS oldest
                    FROM outbox WHERE published_at IS NULL AND parked_at IS NULL
                    """)
                    .query().singleRow();
            parked.set(jdbc.sql("SELECT count(*) FROM outbox WHERE parked_at IS NOT NULL").query(Long.class).single());
            backlog.set(((Number) stats.get("backlog")).longValue());
            failing.set(((Number) stats.get("failing")).longValue());
            Object oldest = stats.get("oldest");
            oldestAgeSeconds.set(oldest == null ? 0 : Math.max(0,
                    Duration.between(toOffset(oldest).toInstant(), clock.instant()).toSeconds()));
        } catch (RuntimeException e) {
            log.debug("Outbox monitor refresh failed: {}", e.toString());
        }
    }

    long backlog() {
        return backlog.get();
    }

    long oldestAgeSeconds() {
        return oldestAgeSeconds.get();
    }

    long parked() {
        return parked.get();
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
    }
}
