package com.loofie.core.job.timer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import com.loofie.core.job.app.JobNoticeService;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TimerRefunds;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.infra.JobDeadlineRepository;
import com.loofie.core.job.infra.JobDeadlineRepository.DueJob;
import com.loofie.core.job.infra.JobDeadlineRepository.DueNotice;
import com.loofie.core.job.timer.TimerRules.Action;
import com.loofie.core.job.timer.TimerRules.Notice;

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
 * events for a notification service to deliver, and are logged in {@code job_events} as {@code TimerFired}
 * (see {@link JobNoticeService}).
 *
 * <p><b>Failing jobs back off.</b> A job whose timer action fails is left out of the query for
 * {@link #FAILURE_BACKOFF}, so a handful of broken jobs can never fill every batch and starve the
 * rest. The backoff is kept in memory: after a restart a broken job is tried once more, which is
 * harmless.
 */
@Component
@EnableConfigurationProperties(TimerProperties.class)
public class DeadlineWorker {

    static final String DEADLINE_NOTICE = JobNoticeService.DEADLINE_NOTICE;
    static final Duration FAILURE_BACKOFF = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(DeadlineWorker.class);

    /** What one run did. */
    public record Result(int transitioned, int noticesSent, int skipped) {
        public boolean didWork() {
            return transitioned + noticesSent > 0;
        }
    }

    private final JobDeadlineRepository deadlines;
    private final JobTransitionService engine;
    private final JobNoticeService notices;
    private final Clock clock;
    private final TimerProperties props;
    private final MeterRegistry meters;
    private final TimerRefunds refunds;

    private final Map<UUID, Instant> backoff = new ConcurrentHashMap<>();
    private final AtomicLong oldestDueAgeSeconds = new AtomicLong();
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();

    DeadlineWorker(JobDeadlineRepository deadlines, JobTransitionService engine, JobNoticeService notices, Clock clock,
                   TimerProperties props, MeterRegistry meters, TimerRefunds refunds) {
        this.refunds = refunds;
        this.deadlines = deadlines;
        this.engine = engine;
        this.notices = notices;
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
            List<DueJob> due = dueForAction(rule, now, excluded);
            if (!due.isEmpty() && (oldestDue == null || due.getFirst().dueAt().isBefore(oldestDue))) {
                oldestDue = due.getFirst().dueAt();
            }
            for (DueJob d : due) {
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

    private List<DueJob> dueForAction(Action rule, Instant now, List<UUID> excluded) {
        return deadlines.due(rule.status(), rule.deadline(), now, excluded, props.batchSize());
    }

    private boolean act(UUID jobId, Action rule, Instant now) {
        try {
            if (rule.action() == JobAction.REPLACEMENT_EXPIRED) {
                // Money moves only after approval: ask for the refund instead of refunding (the job is held meanwhile,
                // so it is not due again).
                refunds.replacementExpired(jobId, now);
            } else {
                engine.transition(jobId, rule.action(), Actor.system(), null);
            }
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

    private List<DueNotice> dueForNotice(Notice rule, Instant now) {
        return deadlines.dueNotices(rule.kind(), rule.status(), rule.deadline(), rule.before(), now, props.batchSize());
    }

    /** Raises the notice (log + event, once); false if it was already raised. */
    private boolean send(Notice rule, DueNotice due, Instant now) {
        try {
            boolean ok = notices.raise(new JobNoticeService.Notice(due.jobId(), due.orderId(), due.version(),
                    rule.status(), rule.kind(), due.dueAt(), rule.audience().stream().map(Enum::name).toList()), now);
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
}
