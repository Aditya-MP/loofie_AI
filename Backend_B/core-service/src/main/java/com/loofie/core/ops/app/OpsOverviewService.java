package com.loofie.core.ops.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.loofie.core.creator.app.OpsCreatorSearch;
import com.loofie.core.job.app.OpsJobStats;
import com.loofie.core.job.app.OpsTimeService;
import com.loofie.core.job.app.PayoutHoldService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.messaging.OutboxAdmin;
import com.loofie.core.refund.app.RefundService;

/**
 * F10 ops home: what needs a person now, and how ops is doing. Built from the features' own counts at most every
 * {@code loofie.ops.overview-refresh} (30 s) and served from memory with its {@code asOf} time, so a click never
 * counts a million jobs (F10 decision 5).
 */
@Service
public class OpsOverviewService {

    private static final Logger log = LoggerFactory.getLogger(OpsOverviewService.class);
    static final Duration PERFORMANCE_WINDOW = Duration.ofDays(30);

    public record Queues(long profilesToReview, long screenshotsToCheck, long disputes, long disputesOverdue,
                         long jobsWaitingForCreator, long openSwapOffers, long refundRequests, long payoutHolds,
                         long parkedEvents) {
    }

    public record Overview(Instant asOf, OpsJobStats.Jobs jobs, Queues queues, OpsJobStats.Performance last30Days,
                           Double opsMinutesPerClosedJob30Days) {
    }

    private final OpsJobStats jobStats;
    private final OpsCreatorSearch creators;
    private final RefundService refunds;
    private final PayoutHoldService holds;
    private final OutboxAdmin outbox;
    private final OpsTimeService time;
    private final Clock clock;
    private final Duration maxAge;
    private final AtomicReference<Overview> latest = new AtomicReference<>();

    public OpsOverviewService(OpsJobStats jobStats, OpsCreatorSearch creators, RefundService refunds,
                              PayoutHoldService holds, OutboxAdmin outbox, OpsTimeService time, Clock clock,
                              @Value("${loofie.ops.overview-refresh:30s}") Duration maxAge) {
        this.jobStats = jobStats;
        this.creators = creators;
        this.refunds = refunds;
        this.holds = holds;
        this.outbox = outbox;
        this.time = time;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    public Overview overview(Actor actor) {
        actor.requireRole(ActorRole.OPS, "Ops only");
        Overview o = latest.get();
        return o == null || stale(o) ? refreshIfStale() : o;
    }

    private boolean stale(Overview o) {
        return o.asOf().plus(maxAge).isBefore(clock.instant());
    }

    /** First call after start, or the background refresh stopped: compute now, once for all waiting callers. */
    private synchronized Overview refreshIfStale() {
        Overview o = latest.get();
        return o == null || stale(o) ? refresh() : o;
    }

    /** Recomputes the overview (every {@code overview-refresh} in the background, or on demand). */
    public synchronized Overview refresh() {
        Instant now = clock.instant();
        OpsJobStats.Jobs jobs = jobStats.jobs(now);
        OpsCreatorSearch.Queues c = creators.queues();
        Queues q = new Queues(c.profilesToReview(), c.screenshotsToCheck(), jobs.byStatus().getOrDefault("DISPUTED", 0L),
                jobs.overdue().getOrDefault("DISPUTE_REVIEW", 0L), jobs.waitingForCreator(), jobs.openSwapOffers(),
                refunds.openCount(), holds.openHolds(), outbox.parkedCount());
        Instant since = now.minus(PERFORMANCE_WINDOW);
        Overview o = new Overview(now, jobs, q, jobStats.performance(since), time.minutesPerClosedJob(since));
        latest.set(o);
        return o;
    }

    @Scheduled(fixedDelayString = "${loofie.ops.overview-refresh:30s}", initialDelayString = "20s")
    void refreshInBackground() {
        try {
            if (latest.get() != null) { // only once someone has opened it: no work for an unused console
                refresh();
            }
        } catch (RuntimeException e) {
            log.warn("Ops overview refresh failed: {}", e.toString());
        }
    }
}
