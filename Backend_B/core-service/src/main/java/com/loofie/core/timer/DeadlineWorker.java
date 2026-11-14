package com.loofie.core.timer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.messaging.EventEnvelope;
import com.loofie.core.messaging.Outbox;
import com.loofie.core.messaging.Topics;
import com.loofie.core.timer.TimerRules.Action;
import com.loofie.core.timer.TimerRules.Notice;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Acts on job deadlines that have passed. Everything it needs is in the database (deadline columns
 * and {@code deadline_notices}), so timers survive restarts. It runs on the one instance holding the
 * "deadline-worker" lease; even if two ran, a double transition loses on the job's optimistic lock
 * and a double notice loses on the notices primary key.
 *
 * <p>State changes go through {@link JobTransitionService} as SYSTEM, one transaction per job, so a
 * failure on one job never blocks the others. Notices go to the outbox as {@code JobDeadlineNotice}
 * events for a notification service to deliver.
 *
 * <p><b>Failing jobs back off.</b> A job whose timer action fails is left out of the query for
 * {@link #FAILURE_BACKOFF}, so a handful of broken jobs can never fill every batch and starve the
 * rest. The backoff is kept in memory: after a restart a broken job is tried once more, which is
 * harmless.
 */
@Component
@EnableConfigurationProperties(TimerProperties.class)
public class DeadlineWorker {

    static final String DEADLINE_NOTICE = "JobDeadlineNotice";
    static final Duration FAILURE_BACKOFF = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(DeadlineWorker.class);

    /** What one run did. */
    public record Result(int transitioned, int noticesSent, int skipped) {
        public boolean didWork() {
            return transitioned + noticesSent > 0;
        }
    }

    private final JdbcClient jdbc;
    private final JobTransitionService engine;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final TimerProperties props;
    private final MeterRegistry meters;

    private final Map<UUID, Instant> backoff = new ConcurrentHashMap<>();
    private final AtomicLong oldestDueAgeSeconds = new AtomicLong();
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();

    DeadlineWorker(JdbcClient jdbc, JobTransitionService engine, Outbox outbox, TransactionTemplate tx, Clock clock,
                   TimerProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.meters = meters;
        Gauge.builder("loofie.timer.oldest.due.age.seconds", oldestDueAgeSeconds, AtomicLong::get)
                .description("How late the most overdue timer action was at the last run").baseUnit("seconds")
                .register(meters);
        Gauge.builder("loofie.timer.last.run.age.seconds", this, DeadlineWorker::secondsSinceLastRun)
                .description("Seconds since the worker last completed a run; climbs if it stops").baseUnit("seconds")
                .register(meters);
        Gauge.builder("loofie.timer.backoff.jobs", backoff, Map::size)
                .description("Jobs whose timer action failed and is waiting to be retried").register(meters);
    }

    public Result runOnce() {
        Instant now = clock.instant();
        backoff.values().removeIf(until -> !until.isAfter(now));
        List<UUID> excluded = List.copyOf(backoff.keySet());

        int transitioned = 0;
        int sent = 0;
        int skipped = 0;
        Instant oldestDue = null;
        for (Action rule : TimerRules.ACTIONS) {
            List<Due> due = dueForAction(rule, now, excluded);
            if (!due.isEmpty() && (oldestDue == null || due.getFirst().dueAt().isBefore(oldestDue))) {
                oldestDue = due.getFirst().dueAt();
            }
            for (Due d : due) {
                if (act(d.jobId(), rule, now)) {
                    transitioned++;
                } else {
                    skipped++;
                }
            }
        }
        for (Notice rule : TimerRules.NOTICES) {
            for (DueNotice due : dueForNotice(rule, now)) {
                if (send(rule, due, now)) {
                    sent++;
                }
            }
        }
        oldestDueAgeSeconds.set(oldestDue == null ? 0 : Math.max(0, Duration.between(oldestDue, now).toSeconds()));
        lastRunAt.set(clock.instant());
        return new Result(transitioned, sent, skipped);
    }

    /** Jobs currently on failure backoff (for tests and diagnostics). */
    boolean isBackingOff(UUID jobId) {
        return backoff.containsKey(jobId);
    }

    private record Due(UUID jobId, Instant dueAt) {
    }

    private List<Due> dueForAction(Action rule, Instant now, List<UUID> excluded) {
        String col = "j." + rule.dueColumn(); // from the fixed TimerRules list, never from input
        String skip = excluded.isEmpty() ? "" : " AND j.id NOT IN (:excluded)";
        var spec = jdbc.sql("SELECT j.id, " + col + " AS due_at FROM jobs j WHERE j.status = :status AND " + col
                        + " <= :now" + skip + " ORDER BY " + col + " LIMIT :limit")
                .param("status", rule.status().name())
                .param("now", utc(now))
                .param("limit", props.batchSize());
        if (!excluded.isEmpty()) {
            spec = spec.param("excluded", excluded);
        }
        return spec.query((rs, n) -> new Due(rs.getObject("id", UUID.class),
                rs.getObject("due_at", OffsetDateTime.class).toInstant())).list();
    }

    private boolean act(UUID jobId, Action rule, Instant now) {
        try {
            engine.transition(jobId, rule.action(), Actor.system(), null);
            count(rule, "moved");
            return true;
        } catch (ObjectOptimisticLockingFailureException e) {
            // Someone else moved the job first (a user click): nothing to do, it will not be due again.
            log.debug("Timer {} lost a race on job {}", rule.action(), jobId);
            count(rule, "race");
            return false;
        } catch (IllegalTransitionException | TransitionGuardException e) {
            // The deadline says "due" but the rules say no: a data problem. Back off and tell someone.
            log.error("Timer {} refused for job {} although its deadline passed; retry in {}: {}", rule.action(), jobId,
                    FAILURE_BACKOFF, e.getMessage());
            backoff.put(jobId, now.plus(FAILURE_BACKOFF));
            count(rule, "refused");
            return false;
        } catch (RuntimeException e) {
            log.error("Timer {} failed for job {}; retry in {}", rule.action(), jobId, FAILURE_BACKOFF, e);
            backoff.put(jobId, now.plus(FAILURE_BACKOFF));
            count(rule, "error");
            return false;
        }
    }

    private void count(Action rule, String outcome) {
        meters.counter("loofie.timer.actions", "action", rule.action().name(), "outcome", outcome).increment();
    }

    private record DueNotice(UUID jobId, UUID orderId, long version, Instant dueAt) {
    }

    private List<DueNotice> dueForNotice(Notice rule, Instant now) {
        String col = "j." + rule.column(); // from the fixed TimerRules list, never from input
        String due = "(" + col + " - make_interval(secs => :beforeSecs))";
        // "col <= now + before" (not "col - before <= now") so the column's index is used.
        return jdbc.sql("SELECT j.id, j.order_id, j.version, " + due + " AS due_at FROM jobs j"
                        + " WHERE j.status = :status AND " + col + " <= :horizon"
                        + " AND NOT EXISTS (SELECT 1 FROM deadline_notices n"
                        + "   WHERE n.job_id = j.id AND n.kind = :kind AND n.due_at = " + due + ")"
                        + " ORDER BY " + col + " LIMIT :limit")
                .param("status", rule.status().name())
                .param("kind", rule.kind())
                .param("beforeSecs", (double) rule.before().toSeconds())
                .param("horizon", utc(now.plus(rule.before())))
                .param("limit", props.batchSize())
                .query((rs, n) -> new DueNotice(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getLong("version"), rs.getObject("due_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Records the notice and enqueues its event in one transaction; false if it was already sent. */
    private boolean send(Notice rule, DueNotice due, Instant now) {
        try {
            Boolean inserted = tx.execute(status -> {
                UUID eventId = Ids.newId();
                int rows = jdbc.sql("""
                        INSERT INTO deadline_notices (job_id, kind, due_at, event_id, sent_at)
                        VALUES (:jobId, :kind, :dueAt, :eventId, :now)
                        ON CONFLICT DO NOTHING
                        """)
                        .param("jobId", due.jobId()).param("kind", rule.kind()).param("dueAt", utc(due.dueAt()))
                        .param("eventId", eventId).param("now", utc(now))
                        .update();
                if (rows == 0) {
                    return false;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("jobId", due.jobId().toString());
                payload.put("orderId", due.orderId().toString());
                payload.put("kind", rule.kind());
                payload.put("jobStatus", rule.status().name());
                payload.put("dueAt", due.dueAt().toString());
                payload.put("audience", rule.audience().stream().map(Enum::name).toList());
                // aggregateVersion = the job's version when the notice was raised. It is NOT unique per
                // event (a JobStateChanged has the same version); consumers order notices by dueAt.
                outbox.enqueue(Topics.CORE_JOB, due.jobId().toString(), new EventEnvelope(eventId, DEADLINE_NOTICE, 1,
                        now, EventEnvelope.PRODUCER, due.jobId(), due.version(), eventId, null, payload));
                return true;
            });
            boolean ok = Boolean.TRUE.equals(inserted);
            if (ok) {
                meters.counter("loofie.timer.notices", "kind", rule.kind()).increment();
            }
            return ok;
        } catch (RuntimeException e) {
            log.warn("Notice {} failed for job {}; will retry next run: {}", rule.kind(), due.jobId(), e.toString());
            return false;
        }
    }

    private double secondsSinceLastRun() {
        Instant last = lastRunAt.get();
        return last == null ? 0 : Math.max(0, Duration.between(last, clock.instant()).toSeconds());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
