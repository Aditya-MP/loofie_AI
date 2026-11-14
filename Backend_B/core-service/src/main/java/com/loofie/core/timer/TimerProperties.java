package com.loofie.core.timer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled      run the scheduled worker (off in tests, which call {@link DeadlineWorker#runOnce()} directly)
 * @param pollInterval delay between runs
 * @param batchSize    max jobs handled per rule per run
 */
@ConfigurationProperties("loofie.timers")
public record TimerProperties(boolean enabled, Duration pollInterval, int batchSize) {

    public TimerProperties {
        if (pollInterval == null) {
            pollInterval = Duration.ofSeconds(30);
        }
        if (batchSize <= 0) {
            batchSize = 100;
        }
    }
}
