package com.loofie.core.draft.app;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the media worker and the video retention sweep. Off with {@code loofie.media.worker-enabled=false} (videos then
 * wait in the queue; nothing is lost). If FFmpeg or the watermark font is missing here, the worker does not start and
 * says why: videos wait rather than fail.
 */
@Component
@ConditionalOnProperty(name = "loofie.media.worker-enabled", havingValue = "true", matchIfMissing = true)
class DraftWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(DraftWorkerScheduler.class);

    private final DraftWorker worker;
    private final DraftRetention retention;
    private volatile boolean toolsOk;

    DraftWorkerScheduler(DraftWorker worker, DraftRetention retention) {
        this.worker = worker;
        this.retention = retention;
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkTools() {
        String problem = worker.toolsProblem();
        toolsOk = problem == null;
        if (!toolsOk) {
            log.error("F7 media worker NOT running: {}. Uploaded videos wait in the queue until this is fixed.", problem);
        }
    }

    @Scheduled(fixedDelayString = "${loofie.media.poll-interval:3s}", initialDelayString = "10s")
    void run() {
        if (!toolsOk) {
            return;
        }
        try {
            for (int round = 0; round < 20 && !Thread.currentThread().isInterrupted(); round++) {
                Map<DraftProcessor.Outcome, Integer> r = worker.runOnce();
                if (r.isEmpty()) {
                    return;
                }
                log.info("Media worker: {}", r);
            }
        } catch (RuntimeException e) {
            log.warn("Media worker run failed: {}", e.toString()); // e.g. database briefly away; next run retries
        }
    }

    @Scheduled(fixedDelayString = "${loofie.media.retention-interval:30m}", initialDelayString = "5m")
    void sweep() {
        try {
            DraftRetention.Result r = retention.runOnce();
            if (r.purged() > 0 || r.scheduled() > 0) {
                log.info("Video retention: {} videos queued for removal, {} jobs given a removal date", r.purged(),
                        r.scheduled());
            }
        } catch (RuntimeException e) {
            log.warn("Video retention run failed: {}", e.toString());
        }
    }
}
