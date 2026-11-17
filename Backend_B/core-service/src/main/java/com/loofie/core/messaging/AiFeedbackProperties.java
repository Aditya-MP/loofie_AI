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
 * @param requireHttps   production: a URL that is not https, or a URL without a key, stops the app at start (a typo
 *                       must fail loudly, not quietly queue events forever). No URL at all is allowed: events wait.
 */
@ConfigurationProperties("loofie.ai-feedback")
public record AiFeedbackProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout,
                                   boolean requireHttps) {

    public AiFeedbackProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        this(baseUrl, apiKey, connectTimeout, requestTimeout, false);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AiFeedbackProperties {
        boolean urlSet = baseUrl != null && !baseUrl.isBlank();
        if (requireHttps && urlSet && !baseUrl.trim().startsWith("https://")) {
            throw new IllegalStateException("loofie.ai-feedback.base-url must be https in production: " + baseUrl);
        }
        if (requireHttps && urlSet && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("loofie.ai-feedback.base-url is set but AI_FEEDBACK_API_KEY is missing");
        }
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
