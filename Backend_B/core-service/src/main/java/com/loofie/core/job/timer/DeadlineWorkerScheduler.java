package com.loofie.core.job.timer;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.loofie.core.common.LeaderLease;

/**
 * Runs the deadline worker on a fixed delay, on the one instance holding the "deadline-worker" lease.
 * Disabled with loofie.timers.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "loofie.timers.enabled", havingValue = "true", matchIfMissing = true)
class DeadlineWorkerScheduler {

    static final String LEASE = "deadline-worker";
    /** Longer than one round (a few hundred short transactions); renewed before every round. */
    private static final Duration LEASE_TTL = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(DeadlineWorkerScheduler.class);

    /** Safety stop so one run cannot loop forever on a huge backlog; the next run continues. */
    private static final int MAX_ROUNDS = 50;

    private final DeadlineWorker worker;
    private final LeaderLease lease;

    DeadlineWorkerScheduler(DeadlineWorker worker, LeaderLease lease) {
        this.worker = worker;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${loofie.timers.poll-interval:30s}")
    void run() {
        try {
            for (int round = 0; round < MAX_ROUNDS && !Thread.currentThread().isInterrupted(); round++) {
                if (!lease.tryAcquire(LEASE, LEASE_TTL)) {
                    return; // another instance runs the timers
                }
                DeadlineWorker.Result r = worker.runOnce();
                if (!r.didWork()) {
                    return; // nothing more due right now
                }
                log.info("Timers: {} jobs moved on, {} notices sent, {} skipped", r.transitioned(), r.noticesSent(),
                        r.skipped());
            }
        } catch (RuntimeException e) {
            // e.g. database briefly unavailable; the next run retries
            log.warn("Deadline worker run failed: {}", e.toString());
        }
    }
}
