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
 *   <li>delivered outbox rows (the receiver has them; kept {@code outbox} long for debugging),</li>
 *   <li>parked outbox rows (rejected by the receiver; kept {@code outboxParked} for investigation and requeue),</li>
 *   <li>idempotency keys (clients retry within minutes, never after a day),</li>
 *   <li>notifications (an inbox is for what is happening now; 90 days).</li>
 * </ul>
 * Deletes go in small batches so they never lock a table for long. {@code job_events} is the audit
 * log and is never deleted.
 */
@Component
@EnableConfigurationProperties(RetentionJob.RetentionProperties.class)
public class RetentionJob {

    @ConfigurationProperties("loofie.retention")
    public record RetentionProperties(boolean enabled, Duration interval, Duration outbox, Duration outboxParked,
                                      Duration idempotencyKeys, Duration notifications, Duration auditLog,
                                      int batchSize) {
        public RetentionProperties {
            interval = interval == null ? Duration.ofMinutes(10) : interval;
            outbox = outbox == null ? Duration.ofDays(7) : outbox;
            outboxParked = outboxParked == null ? Duration.ofDays(30) : outboxParked;
            idempotencyKeys = idempotencyKeys == null ? Duration.ofHours(24) : idempotencyKeys;
            notifications = notifications == null ? Duration.ofDays(90) : notifications;
            auditLog = auditLog == null ? Duration.ofDays(365) : auditLog;
            batchSize = batchSize <= 0 ? 5000 : batchSize;
        }
    }

    /** What one run deleted. */
    public record Result(long outbox, long parked, long idempotencyKeys, long notifications, long identity) {
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
        long parked = deleteInBatches("""
                DELETE FROM outbox WHERE id IN (
                    SELECT id FROM outbox WHERE parked_at IS NOT NULL AND parked_at < :before LIMIT :limit)
                """, now.minus(props.outboxParked()));
        long keys = deleteInBatches("""
                DELETE FROM idempotency_keys WHERE (scope, idem_key) IN (
                    SELECT scope, idem_key FROM idempotency_keys WHERE created_at < :before LIMIT :limit)
                """, now.minus(props.idempotencyKeys()));
        long inbox = deleteInBatches("""
                DELETE FROM notifications WHERE id IN (
                    SELECT id FROM notifications WHERE created_at < :before LIMIT :limit)
                """, now.minus(props.notifications()));
        long identity = identity(now);
        if (outbox + parked + keys + inbox + identity > 0) {
            log.info("Retention: deleted {} outbox, {} parked outbox, {} idempotency, {} notification, {} identity rows",
                    outbox, parked, keys, inbox, identity);
        }
        return new Result(outbox, parked, keys, inbox, identity);
    }

    /**
     * F1: OTP challenges after a day (codes live 5 minutes), sessions a week after they ended (kept briefly to answer
     * "was I logged out?"), rate counters after two days, unused staff invites a month after expiry, and the audit log
     * after {@code auditLog} (1 year: CERT-In needs 180 days, DPDP logs 1 year). Consents are never deleted here.
     */
    private long identity(Instant now) {
        long n = deleteInBatches("""
                DELETE FROM otp_challenges WHERE id IN (
                    SELECT id FROM otp_challenges WHERE created_at < :before LIMIT :limit)
                """, now.minus(Duration.ofDays(1)));
        n += deleteInBatches("""
                DELETE FROM sessions WHERE id IN (
                    SELECT id FROM sessions WHERE expires_at < :before LIMIT :limit)
                """, now.minus(Duration.ofDays(7)));
        n += deleteInBatches("""
                DELETE FROM sessions WHERE id IN (
                    SELECT id FROM sessions WHERE revoked_at < :before LIMIT :limit)
                """, now.minus(Duration.ofDays(7)));
        n += deleteInBatches("""
                DELETE FROM rate_counters WHERE (key, window_start) IN (
                    SELECT key, window_start FROM rate_counters WHERE window_start < :before LIMIT :limit)
                """, now.minus(Duration.ofDays(2)));
        n += deleteInBatches("""
                DELETE FROM invites WHERE id IN (
                    SELECT id FROM invites WHERE accepted_at IS NULL AND expires_at < :before LIMIT :limit)
                """, now.minus(Duration.ofDays(30)));
        n += deleteInBatches("""
                DELETE FROM audit_log WHERE id IN (
                    SELECT id FROM audit_log WHERE at < :before LIMIT :limit)
                """, now.minus(props.auditLog()));
        return n;
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
