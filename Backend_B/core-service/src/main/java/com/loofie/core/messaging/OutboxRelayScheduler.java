package com.loofie.core.messaging;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.loofie.core.common.LeaderLease;

/**
 * Runs the relay on a fixed delay, on the one instance holding the "outbox-relay" lease.
 * Disabled with loofie.outbox.relay.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "loofie.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

    static final String LEASE = "outbox-relay";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    /** Safety stop per run so the lease is renewed regularly; the next run continues. */
    private static final int MAX_BATCHES = 50;

    private final OutboxRelay relay;
    private final EventSender sender;
    private final LeaderLease lease;
    private final Duration leaseTtl;

    OutboxRelayScheduler(OutboxRelay relay, EventSender sender, LeaderLease lease, OutboxProperties props) {
        this.relay = relay;
        this.sender = sender;
        this.lease = lease;
        // Longer than the slowest possible batch (it stops starting sends after MAX_WAVES x sendTimeout), plus margin.
        this.leaseTtl = props.sendTimeout().multipliedBy(OutboxRelay.MAX_WAVES + 2);
    }

    @Scheduled(fixedDelayString = "${loofie.outbox.relay.poll-interval:500ms}")
    void run() {
        if (!sender.ready()) {
            return; // receiver not configured here: rows wait (and are sent once it is)
        }
        try {
            for (int i = 0; i < MAX_BATCHES && !Thread.currentThread().isInterrupted(); i++) {
                if (!lease.tryAcquire(LEASE, leaseTtl)) {
                    return; // another instance is the relay
                }
                if (relay.publishBatch() == 0) {
                    return; // backlog drained
                }
            }
        } catch (RuntimeException e) {
            // e.g. database briefly unavailable; the next run retries
            log.warn("Outbox relay run failed: {}", e.toString());
        }
    }
}
