package com.loofie.core.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backend A's {@code POST /ai/feedback}. With no base URL or no API key the relay does not run and rows wait
 * (they are sent once it is configured).
 *
 * @param baseUrl        e.g. {@code https://ai.internal.loofie.in}; empty = not configured here
 * @param apiKey         Core's own key, sent as {@code X-API-Key}; from an environment variable, never the repo
 * @param connectTimeout opening a connection
 * @param requestTimeout one request, end to end; below {@code loofie.outbox.relay.send-timeout}
 */
@ConfigurationProperties("loofie.ai-feedback")
public record AiFeedbackProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout) {

    public AiFeedbackProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(5);
        }
    }

    boolean configured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
