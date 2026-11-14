package com.loofie.core.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the relay on a fixed delay. Disabled with loofie.outbox.relay.enabled=false. */
@Component
@ConditionalOnProperty(name = "loofie.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${loofie.outbox.relay.poll-interval:500ms}")
    void run() {
        try {
            int sent;
            do {
                sent = relay.publishBatch();
            } while (sent > 0 && !Thread.currentThread().isInterrupted()); // drain backlog, then wait
        } catch (RuntimeException e) {
            // e.g. database briefly unavailable; the next run retries
            log.warn("Outbox relay run failed: {}", e.toString());
        }
    }
}
