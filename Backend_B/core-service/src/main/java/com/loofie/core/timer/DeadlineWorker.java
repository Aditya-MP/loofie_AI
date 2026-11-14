package com.loofie.core.timer;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/**
 * Acts on job deadlines that have passed. Everything it needs is in the database (deadline columns
 * and {@code deadline_notices}), so timers survive restarts and it is safe to run on several
 * instances at once: a double transition loses on the job's optimistic lock, and a double notice
 * loses on the notices primary key.
 *
 * <p>State changes go through {@link JobTransitionService} as SYSTEM, one transaction per job, so a
 * failure on one job never blocks the others. Notices go to the outbox as {@code JobDeadlineNotice}
 * events for a notification service to deliver.
 */
@Component
@EnableConfigurationProperties(TimerProperties.class)
public class DeadlineWorker {

    static final String DEADLINE_NOTICE = "JobDeadlineNotice";

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

    DeadlineWorker(JdbcClient jdbc, JobTransitionService engine, Outbox outbox, TransactionTemplate tx, Clock clock,
                   TimerProperties props) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
    }

    public Result runOnce() {
        Instant now = clock.instant();
        int transitioned = 0;
        int sent = 0;
        int skipped = 0;
        for (Action rule : TimerRules.ACTIONS) {
            for (UUID jobId : dueForAction(rule, now)) {
                if (act(jobId, rule)) {
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
        return new Result(transitioned, sent, skipped);
    }

    private List<UUID> dueForAction(Action rule, Instant now) {
        // dueColumn comes from the fixed TimerRules list, never from input.
        return jdbc.sql("SELECT j.id FROM jobs j WHERE j.status = :status AND j." + rule.dueColumn() + " <= :now"
                        + " ORDER BY j." + rule.dueColumn() + " LIMIT :limit")
                .param("status", rule.status().name())
                .param("now", utc(now))
                .param("limit", props.batchSize())
                .query(UUID.class)
                .list();
    }

    private boolean act(UUID jobId, Action rule) {
        try {
            engine.transition(jobId, rule.action(), Actor.system(), null);
            return true;
        } catch (ObjectOptimisticLockingFailureException | IllegalTransitionException | TransitionGuardException e) {
            // Someone else moved the job first (a user click, another instance): nothing to do.
            log.debug("Timer {} skipped for job {}: {}", rule.action(), jobId, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("Timer {} failed for job {}; will retry next run: {}", rule.action(), jobId, e.toString());
            return false;
        }
    }

    private record DueNotice(UUID jobId, UUID orderId, long version, Instant dueAt) {
    }

    private List<DueNotice> dueForNotice(Notice rule, Instant now) {
        String due = "(" + rule.dueSql() + ")"; // from the fixed TimerRules list, never from input
        return jdbc.sql("SELECT j.id, j.order_id, j.version, " + due + " AS due_at FROM jobs j"
                        + " WHERE j.status = :status AND " + due + " IS NOT NULL AND " + due + " <= :now"
                        + " AND NOT EXISTS (SELECT 1 FROM deadline_notices n"
                        + "   WHERE n.job_id = j.id AND n.kind = :kind AND n.due_at = " + due + ")"
                        + " ORDER BY due_at LIMIT :limit")
                .param("status", rule.status().name())
                .param("kind", rule.kind())
                .param("now", utc(now))
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
                outbox.enqueue(Topics.CORE_JOB, due.jobId().toString(), new EventEnvelope(eventId, DEADLINE_NOTICE, 1,
                        now, EventEnvelope.PRODUCER, due.jobId(), due.version(), eventId, null, payload));
                return true;
            });
            return Boolean.TRUE.equals(inserted);
        } catch (RuntimeException e) {
            log.warn("Notice {} failed for job {}; will retry next run: {}", rule.kind(), due.jobId(), e.toString());
            return false;
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
