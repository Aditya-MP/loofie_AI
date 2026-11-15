package com.loofie.core.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled      run the scheduled relay (off in tests, which call it directly)
 * @param pollInterval delay between relay runs
 * @param batchSize    max rows read per run
 * @param sendTimeout  how long to wait for one group of sends to be answered
 * @param maxInFlight  max requests open at once to the receiver (one Backend A server must not be flooded)
 */
@ConfigurationProperties("loofie.outbox.relay")
public record OutboxProperties(boolean enabled, Duration pollInterval, int batchSize, Duration sendTimeout,
                               int maxInFlight) {

    public OutboxProperties {
        if (pollInterval == null) {
            pollInterval = Duration.ofMillis(500);
        }
        if (batchSize <= 0) {
            batchSize = 200;
        }
        if (sendTimeout == null) {
            sendTimeout = Duration.ofSeconds(10);
        }
        if (maxInFlight <= 0) {
            maxInFlight = 32;
        }
    }
}
