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
        server.start();
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
