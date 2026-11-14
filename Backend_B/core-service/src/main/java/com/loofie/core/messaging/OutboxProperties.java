package com.loofie.core.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled     run the scheduled relay (off in tests, which call it directly)
 * @param pollInterval delay between relay runs
 * @param batchSize   max rows published per run
 * @param sendTimeout how long to wait for the broker to acknowledge one message
 */
@ConfigurationProperties("loofie.outbox.relay")
public record OutboxProperties(boolean enabled, Duration pollInterval, int batchSize, Duration sendTimeout) {

    public OutboxProperties {
        if (pollInterval == null) {
            pollInterval = Duration.ofMillis(500);
        }
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (sendTimeout == null) {
            sendTimeout = Duration.ofSeconds(10);
        }
    }
}
