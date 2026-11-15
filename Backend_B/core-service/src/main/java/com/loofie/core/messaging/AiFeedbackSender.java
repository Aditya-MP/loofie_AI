package com.loofie.core.messaging;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sends outbox rows to Backend A's {@code POST /ai/feedback}. Retries are safe: every request carries our
 * {@code event_id}, and Backend A answers a repeat with {@code duplicate: true} without saving it twice.
 *
 * <table>
 *   <tr><th>Answer</th><th>Result</th></tr>
 *   <tr><td>2xx</td><td>{@link Delivery#DELIVERED}</td></tr>
 *   <tr><td>409 (event id already saved with other content)</td><td>{@link Delivery#CONFLICT}: they have it</td></tr>
 *   <tr><td>400, 404, 422</td><td>{@link Rejected}: will never be accepted as it is, park it</td></tr>
 *   <tr><td>connection refused, unknown host, connect timeout</td><td>{@link Unreachable}</td></tr>
 *   <tr><td>401, 403, 429, 5xx, request timeout</td><td>other exception: retry with backoff</td></tr>
 * </table>
 */
@Component
public class AiFeedbackSender implements EventSender {

    private static final Logger log = LoggerFactory.getLogger(AiFeedbackSender.class);
    static final String PATH = "/ai/feedback";
    private static final int MAX_BODY_IN_ERROR = 500;

    private final AiFeedbackProperties props;
    private final AiFeedbackMapper mapper;
    private final JsonMapper json;
    private final HttpClient http;
    private final URI endpoint;

    public AiFeedbackSender(AiFeedbackProperties props, AiFeedbackMapper mapper, JsonMapper json) {
        this.props = props;
        this.mapper = mapper;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1) // plain keep-alive connections; no h2c upgrade surprises
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.endpoint = props.configured() ? URI.create(props.baseUrl().replaceAll("/+$", "") + PATH) : null;
        if (!props.configured()) {
            log.warn("loofie.ai-feedback is not configured (base-url / api-key): outbox rows wait and are sent "
                    + "once it is");
        }
    }

    @Override
    public boolean ready() {
        return endpoint != null;
    }

    @Override
    public CompletableFuture<Delivery> send(Message m) {
        Optional<Map<String, Object>> body = mapper.toRequest(m.eventType(), m.envelope());
        if (body.isEmpty()) {
            return CompletableFuture.completedFuture(Delivery.SKIPPED);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(props.requestTimeout())
                .header("Content-Type", "application/json")
                .header("X-API-Key", props.apiKey())
                .header("X-Request-ID", m.id().toString()) // their request log then shows our event id
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body.get())))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        throw classify(error);
                    }
                    return outcome(response.statusCode(), response.body());
                });
    }

    static Delivery outcome(int status, String body) {
        if (status >= 200 && status < 300) {
            return Delivery.DELIVERED;
        }
        if (status == 409) {
            return Delivery.CONFLICT;
        }
        String message = "HTTP " + status + ": " + excerpt(body);
        if (status == 400 || status == 404 || status == 422) {
            throw new Rejected(message);
        }
        throw new IllegalStateException(message); // 401/403 (key), 429, 5xx: retried with backoff
    }

    private static RuntimeException classify(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException
                || cause instanceof UnresolvedAddressException) {
            return new Unreachable(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }
        return cause instanceof RuntimeException re ? re : new IllegalStateException(cause.toString(), cause);
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > MAX_BODY_IN_ERROR ? body.substring(0, MAX_BODY_IN_ERROR) : body;
    }
}
