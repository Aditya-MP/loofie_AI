package com.loofie.core.creator.infra;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.loofie.core.common.DependencyUnavailableException;
import com.loofie.core.creator.domain.InstagramGateway;
import com.loofie.core.messaging.AiFeedbackProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Backend A's Instagram and profile reads (their docs/API.md), server to server with Core's API key. Same base URL
 * and key as the events relay ({@code loofie.ai-feedback.*}). Short timeouts: a creator is waiting on a spinner.
 */
@Component
public class BackendAInstagramClient implements InstagramGateway {

    private static final String DOWN = "Instagram connect is not available right now. Try again in a minute, or "
            + "fill in your numbers by hand.";

    static final Duration VERIFIED_TIMEOUT = Duration.ofMillis(1500);

    private final AiFeedbackProperties props;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http;

    public BackendAInstagramClient(AiFeedbackProperties props, JsonMapper json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public LoginLink loginLink(String accountRef) {
        HttpResponse<String> r = get("/ai/instagram/login-url?account_ref=" + enc(accountRef));
        if (r.statusCode() != 200) {
            throw unavailable(r);
        }
        JsonNode j = json.readTree(r.body());
        String url = j.path("url").asString("");
        if (!url.startsWith("https://")) {
            throw new DependencyUnavailableException(DOWN, null); // never send her to anything but Meta's https page
        }
        return new LoginLink(URI.create(url), clock.instant().plusSeconds(j.path("expires_in").asLong(600)));
    }

    @Override
    public Optional<Connection> connection(String accountRef) {
        HttpResponse<String> r = get("/ai/instagram/accounts/" + enc(accountRef));
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        if (r.statusCode() != 200) {
            throw unavailable(r);
        }
        JsonNode j = json.readTree(r.body());
        return Optional.of(new Connection(text(j, "handle"), text(j, "status"),
                j.path("insights_allowed").asBoolean(false), instant(j, "token_expires_at"),
                instant(j, "last_synced_at"), text(j, "last_error")));
    }

    @Override
    public Optional<Verified> verifiedNumbers(String handle) {
        // The profile page waits on this: a slow Backend A shows "updating" after 1.5 s instead of holding the page.
        HttpResponse<String> r = send(base("/ai/creators/" + enc(handle)).timeout(VERIFIED_TIMEOUT).GET().build());
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        if (r.statusCode() != 200) {
            throw unavailable(r);
        }
        JsonNode j = json.readTree(r.body());
        JsonNode sources = j.path("metric_sources");
        JsonNode stats = j.path("stats");
        Long followers = verified(sources, "followers") ? number(stats, "followers") : null;
        Long views = verified(sources, "median_views") || verified(sources, "views")
                ? number(stats, "median_views") : null;
        if (followers == null && views == null) {
            return Optional.empty();
        }
        return Optional.of(new Verified(followers, capturedOn(sources, "followers"), views,
                Optional.ofNullable(capturedOn(sources, "median_views")).orElse(capturedOn(sources, "views"))));
    }

    @Override
    public void disconnect(String accountRef) {
        HttpResponse<String> r = send(base("/ai/instagram/accounts/" + enc(accountRef)).DELETE().build());
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) {
            throw unavailable(r);
        }
    }

    private HttpResponse<String> get(String path) {
        return send(base(path).GET().build());
    }

    private HttpRequest.Builder base(String path) {
        if (!props.configured()) {
            throw new DependencyUnavailableException(DOWN, null);
        }
        return HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(5)).header("X-API-Key", props.apiKey());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DependencyUnavailableException(DOWN, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException(DOWN, e);
        }
    }

    private static DependencyUnavailableException unavailable(HttpResponse<String> r) {
        return new DependencyUnavailableException(DOWN, new IllegalStateException("Backend A answered "
                + r.statusCode() + " to " + r.request().uri().getPath()));
    }

    private static boolean verified(JsonNode sources, String metric) {
        return "API_VERIFIED".equals(sources.path(metric).path("source").asString(""));
    }

    private static LocalDate capturedOn(JsonNode sources, String metric) {
        String s = sources.path(metric).path("captured_on").asString("");
        try {
            return s.isEmpty() ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long number(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNumber() ? v.asLong() : null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString();
    }

    private static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        try {
            return s == null ? null : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
