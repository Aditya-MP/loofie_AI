package com.loofie.core.housekeeping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.loofie.core.common.LeaderLease;

/**
 * Deletes rows that have done their job, so tables stay small:
 * <ul>
 *   <li>published outbox rows (Kafka has them; kept {@code outbox} long for debugging),</li>
 *   <li>inbox de-duplication rows (kept far longer than any redelivery can happen),</li>
 *   <li>idempotency keys (clients retry within minutes, never after a day).</li>
 * </ul>
 * Deletes go in small batches so they never lock a table for long. {@code job_events} is the audit
 * log and is never deleted.
 */
@Component
@EnableConfigurationProperties(RetentionJob.RetentionProperties.class)
public class RetentionJob {

    @ConfigurationProperties("loofie.retention")
    public record RetentionProperties(boolean enabled, Duration interval, Duration outbox, Duration inbox,
                                      Duration idempotencyKeys, int batchSize) {
        public RetentionProperties {
            interval = interval == null ? Duration.ofMinutes(10) : interval;
            outbox = outbox == null ? Duration.ofDays(7) : outbox;
            inbox = inbox == null ? Duration.ofDays(30) : inbox;
            idempotencyKeys = idempotencyKeys == null ? Duration.ofHours(24) : idempotencyKeys;
            batchSize = batchSize <= 0 ? 5000 : batchSize;
        }
    }

    /** What one run deleted. */
    public record Result(long outbox, long inbox, long idempotencyKeys) {
    }

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    /** Per table per run, so one run stays short; the next run continues. */
    private static final int MAX_BATCHES = 20;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RetentionProperties props;

    RetentionJob(JdbcClient jdbc, Clock clock, RetentionProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.props = props;
    }

    public Result runOnce() {
        Instant now = clock.instant();
        long outbox = deleteInBatches("""
                DELETE FROM outbox WHERE id IN (
                    SELECT id FROM outbox WHERE published_at IS NOT NULL AND published_at < :before LIMIT :limit)
                """, now.minus(props.outbox()));
        long inbox = deleteInBatches("""
                DELETE FROM processed_events WHERE (consumer, event_id) IN (
                    SELECT consumer, event_id FROM processed_events WHERE processed_at < :before LIMIT :limit)
                """, now.minus(props.inbox()));
        long keys = deleteInBatches("""
                DELETE FROM idempotency_keys WHERE (scope, idem_key) IN (
                    SELECT scope, idem_key FROM idempotency_keys WHERE created_at < :before LIMIT :limit)
                """, now.minus(props.idempotencyKeys()));
        if (outbox + inbox + keys > 0) {
            log.info("Retention: deleted {} outbox, {} inbox, {} idempotency rows", outbox, inbox, keys);
        }
        return new Result(outbox, inbox, keys);
    }

    private long deleteInBatches(String sql, Instant before) {
        long total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = jdbc.sql(sql)
                    .param("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                    .param("limit", props.batchSize())
                    .update();
            total += deleted;
            if (deleted < props.batchSize()) {
                break;
            }
        }
        return total;
    }

    /** Scheduled on the instance holding the "retention" lease. */
    @Component
    @ConditionalOnProperty(name = "loofie.retention.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduler {

        private final RetentionJob job;
        private final LeaderLease lease;

        Scheduler(RetentionJob job, LeaderLease lease) {
            this.job = job;
            this.lease = lease;
        }

        @Scheduled(fixedDelayString = "${loofie.retention.interval:10m}", initialDelayString = "1m")
        void run() {
            try {
                if (lease.tryAcquire("retention", Duration.ofMinutes(5))) {
                    job.runOnce();
                }
            } catch (RuntimeException e) {
                log.warn("Retention run failed: {}", e.toString());
            }
        }
    }
}
