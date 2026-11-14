package com.loofie.core.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the deadline worker on a fixed delay. Disabled with loofie.timers.enabled=false. */
@Component
@ConditionalOnProperty(name = "loofie.timers.enabled", havingValue = "true", matchIfMissing = true)
class DeadlineWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadlineWorkerScheduler.class);

    /** Safety stop so one run cannot loop forever on a huge backlog; the next run continues. */
    private static final int MAX_ROUNDS = 50;

    private final DeadlineWorker worker;

    DeadlineWorkerScheduler(DeadlineWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${loofie.timers.poll-interval:30s}")
    void run() {
        try {
            for (int round = 0; round < MAX_ROUNDS && !Thread.currentThread().isInterrupted(); round++) {
                DeadlineWorker.Result r = worker.runOnce();
                if (r.didWork()) {
                    log.info("Timers: {} jobs moved on, {} notices sent, {} skipped", r.transitioned(), r.noticesSent(),
                            r.skipped());
                } else {
                    return; // nothing more due right now
                }
            }
        } catch (RuntimeException e) {
            // e.g. database briefly unavailable; the next run retries
            log.warn("Deadline worker run failed: {}", e.toString());
        }
    }
}
