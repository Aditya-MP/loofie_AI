package com.loofie.core.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for Backend A's {@code POST /ai/feedback} in tests (JDK built-in HTTP server, no extra dependency).
 * Records every request; answers 200 unless a test says otherwise for an event id.
 */
public class FakeAiService {

    public static final String API_KEY = "test-core-key";

    public record Received(String apiKey, String requestId, JsonNode body) {
        public String eventId() {
            return body.get("event_id").asString();
        }

        public JsonNode payload() {
            return body.get("payload");
        }
    }

    /** What to answer: status and body. */
    public record Reply(int status, String body) {
        public static Reply ok(String eventId, boolean duplicate) {
            return new Reply(200, "{\"event_id\":\"" + eventId + "\",\"duplicate\":" + duplicate + "}");
        }
    }

    private final JsonMapper json;
    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Integer, Reply>> scripted = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Duration delay = Duration.ZERO;

    public FakeAiService(JsonMapper json) throws IOException {
        this.json = json;
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 200);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/ai/feedback", this::handle);
        server.createContext("/ai/instagram/", this::creatorSide);
        server.createContext("/ai/creators/", this::creatorSide);
        server.start();
    }

    // ---------------------------------------------------------------- round C: Instagram and creator profile calls

    /** One call to the Instagram / profile endpoints, as received. */
    public record CreatorCall(String method, String path, String query, String apiKey, JsonNode body) {
    }

    private final List<CreatorCall> creatorCalls = new CopyOnWriteArrayList<>();
    private final Map<String, String> connections = new ConcurrentHashMap<>();
    private final Map<String, String> verifiedProfiles = new ConcurrentHashMap<>();
    private volatile boolean creatorSideDown;

    /** What {@code GET /ai/instagram/accounts/{ref}} answers for this ref (Backend A's JSON); unset = 404. */
    public void connection(String ref, String status, String handle, boolean insightsAllowed) {
        connections.put(ref, "{\"account_ref\":\"" + ref + "\",\"handle\":\"" + handle + "\",\"status\":\"" + status
                + "\",\"insights_allowed\":" + insightsAllowed + ",\"token_expires_at\":\"2027-01-13T10:00:00Z\","
                + "\"last_synced_at\":\"2026-11-16T04:30:05Z\",\"last_error\":null}");
    }

    /** What {@code GET /ai/creators/{handle}} answers: verified followers and median views. */
    public void verifiedProfile(String handle, long followers, long medianViews) {
        verifiedProfiles.put(handle, "{\"handle\":\"" + handle + "\",\"stats\":{\"followers\":" + followers
                + ",\"median_views\":" + medianViews + "},\"metric_sources\":{\"followers\":{\"source\":\"API_VERIFIED\","
                + "\"captured_on\":\"2026-11-16\"},\"median_views\":{\"source\":\"API_VERIFIED\",\"captured_on\":"
                + "\"2026-11-16\"}}}");
    }

    /** Every Instagram / profile endpoint answers 503 while down. */
    public void creatorSideDown(boolean down) {
        this.creatorSideDown = down;
    }

    public List<CreatorCall> creatorCalls() {
        return List.copyOf(creatorCalls);
    }

    public void resetCreatorSide() {
        creatorCalls.clear();
        connections.clear();
        verifiedProfiles.clear();
        creatorSideDown = false;
    }

    private void creatorSide(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            byte[] in = ex.getRequestBody().readAllBytes();
            creatorCalls.add(new CreatorCall(method, path, query, ex.getRequestHeaders().getFirst("X-API-Key"),
                    in.length == 0 ? null : json.readTree(in)));
            if (!API_KEY.equals(ex.getRequestHeaders().getFirst("X-API-Key"))) {
                reply(ex, 401, "{\"detail\":\"bad key\"}");
            } else if (creatorSideDown) {
                reply(ex, 503, "{\"detail\":\"down\"}");
            } else if (path.equals("/ai/instagram/login-url")) {
                String ref = query.substring(query.indexOf("account_ref=") + "account_ref=".length());
                reply(ex, 200, "{\"url\":\"https://www.instagram.com/oauth/authorize?client_id=1&state=" + ref
                        + "\",\"expires_in\":600}");
            } else if (path.startsWith("/ai/instagram/accounts/")) {
                String ref = path.substring("/ai/instagram/accounts/".length());
                if (method.equals("DELETE")) {
                    boolean had = connections.remove(ref) != null;
                    reply(ex, had ? 200 : 404, had ? "{\"status\":\"revoked\"}" : "{\"detail\":\"not found\"}");
                } else {
                    String c = connections.get(ref);
                    reply(ex, c == null ? 404 : 200, c == null ? "{\"detail\":\"not found\"}" : c);
                }
            } else if (path.equals("/ai/creators/profile") && method.equals("POST")) {
                reply(ex, 200, "{\"handle\":\"x\",\"quality\":null}");
            } else if (path.startsWith("/ai/creators/")) {
                String p = verifiedProfiles.get(path.substring("/ai/creators/".length()));
                reply(ex, p == null ? 404 : 200, p == null ? "{\"detail\":\"no profile\"}" : p);
            } else {
                reply(ex, 404, "{}");
            }
        } finally {
            ex.close();
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    /** Answers for one event id; the function gets the call number (1 = first call). */
    public void script(String eventId, Function<Integer, Reply> reply) {
        scripted.put(eventId, reply);
    }

    public void delay(Duration d) {
        this.delay = d;
    }

    public void reset() {
        scripted.clear();
        delay = Duration.ZERO;
        maxInFlight.set(0);
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    /** Accepted or not, every request that reached us for this job, in arrival order. */
    public List<Received> forJob(String jobId) {
        return received.stream().filter(r -> jobId.equals(r.payload().path("job_id").asString(""))).toList();
    }

    public int calls(String eventId) {
        AtomicInteger n = calls.get(eventId);
        return n == null ? 0 : n.get();
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            if (!delay.isZero()) {
                Thread.sleep(delay);
            }
            JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
            String eventId = body.get("event_id").asString();
            received.add(new Received(exchange.getRequestHeaders().getFirst("X-API-Key"),
                    exchange.getRequestHeaders().getFirst("X-Request-ID"), body));
            int call = calls.computeIfAbsent(eventId, k -> new AtomicInteger()).incrementAndGet();
            Function<Integer, Reply> script = scripted.get(eventId);
            Reply reply = script != null ? script.apply(call) : Reply.ok(eventId, call > 1);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }
}
