package com.loofie.core.draft.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import com.loofie.core.draft.infra.DraftRepository;
import com.loofie.core.media.MediaProperties;
import com.loofie.core.media.MediaTools;

/**
 * The media queue (D2): every instance takes up to {@code loofie.media.max-concurrent} videos at a time (SKIP LOCKED,
 * so instances never take the same one) under a lease; a worker that dies leaves its video to be taken again once the
 * lease runs out. Scheduling is in {@link DraftWorkerScheduler}; tests call {@link #runOnce()}.
 */
@Component
public class DraftWorker {

    private static final Logger log = LoggerFactory.getLogger(DraftWorker.class);

    private final DraftRepository drafts;
    private final DraftProcessor processor;
    private final MediaTools media;
    private final MediaProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    DraftWorker(DraftRepository drafts, DraftProcessor processor, MediaTools media, MediaProperties props,
                TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.drafts = drafts;
        this.processor = processor;
        this.media = media;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
        Gauge.builder("loofie.drafts.waiting", () -> drafts.backlog().waiting()).register(meters);
    }

    /** Whether the tools are usable here; null when fine. */
    public String toolsProblem() {
        return media.problem();
    }

    /** Takes due videos and works them, at most {@code max-concurrent} at once. Returns what happened to each. */
    public Map<DraftProcessor.Outcome, Integer> runOnce() {
        Instant now = clock.instant();
        List<DraftRepository.Draft> claimed = tx.execute(s -> drafts.claim(now, now.plus(props.lease()),
                props.maxConcurrent()));
        Map<DraftProcessor.Outcome, Integer> result = new EnumMap<>(DraftProcessor.Outcome.class);
        if (claimed == null || claimed.isEmpty()) {
            return result;
        }
        if (claimed.size() == 1) {
            result.merge(processor.process(claimed.getFirst()), 1, Integer::sum);
            return result;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DraftProcessor.Outcome>> running = new ArrayList<>();
            for (DraftRepository.Draft d : claimed) {
                running.add(pool.submit(() -> processor.process(d)));
            }
            for (Future<DraftProcessor.Outcome> f : running) {
                try {
                    result.merge(f.get(), 1, Integer::sum);
                } catch (ExecutionException e) {
                    log.error("Draft processing crashed; the lease returns it to the queue", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return result;
    }
}
