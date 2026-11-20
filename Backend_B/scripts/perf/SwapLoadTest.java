import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * F9 swap offers under load, against a running core-service (local profile, dev headers), jobs from swap-seed.sql.
 * Phases, open loop at {rps}: ops offers a creator (write), the brand looks at the offer (read), the brand admin
 * approves it (write: the engine invites the creator). The first {warmup} jobs go through all three unmeasured (a cold
 * JVM bursts). Gates: writes p95 <= 300 ms, reads p95 <= 50 ms, no errors. Exit 1 on any failure.
 *
 * Usage: java SwapLoadTest.java <rps> <warmup> <swap-jobs.csv (job,brand,creator)>
 */
public class SwapLoadTest {

    static final String BASE = "http://localhost:8081";
    static final String OPS = UUID.randomUUID().toString();
    static final long PAUSE_MS = Long.getLong("pauseMs", 15_000);

    record Row(String job, String brand, String creator) {
    }

    public static void main(String[] args) throws Exception {
        int rps = Integer.parseInt(args[0]);
        int warmup = Integer.parseInt(args[1]);
        List<Row> rows = Files.readAllLines(Path.of(args[2])).stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(l -> l.split(",")).map(p -> new Row(p[0], p[1], p[2])).toList();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        List<Row> warm = rows.subList(0, warmup);
        List<Row> measured = rows.subList(warmup, rows.size());

        run("warm-offer", 201, rps, warm, http, SwapLoadTest::offer, Long.MAX_VALUE);
        run("warm-view", 200, rps, warm, http, SwapLoadTest::view, Long.MAX_VALUE);
        run("warm-appr", 200, rps, warm, http, SwapLoadTest::approve, Long.MAX_VALUE);

        boolean ok = run("OFFER", 201, rps, measured, http, SwapLoadTest::offer, 300);
        Thread.sleep(PAUSE_MS); // let the database settle after a write phase (laptop: checkpoint / autovacuum bursts)
        ok &= run("VIEW", 200, rps, measured, http, SwapLoadTest::view, 50);
        run("VIEW-again", 200, rps, measured, http, SwapLoadTest::view, Long.MAX_VALUE); // diagnosis, not gated
        Thread.sleep(PAUSE_MS);
        ok &= run("APPROVE", 200, rps, measured, http, SwapLoadTest::approve, 300);
        System.exit(ok ? 0 : 1);
    }

    static HttpRequest offer(Row r) {
        return HttpRequest.newBuilder(URI.create(BASE + "/api/core/ops/jobs/" + r.job() + "/swap-offers"))
                .header("X-Dev-Role", "OPS").header("X-Dev-User-Id", OPS).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"creatorId\":\"" + r.creator()
                        + "\",\"note\":\"load test offer\"}")).timeout(Duration.ofSeconds(10)).build();
    }

    static HttpRequest view(Row r) {
        return brand(HttpRequest.newBuilder(URI.create(BASE + "/api/core/brand/jobs/" + r.job() + "/swap-offer")), r,
                "BRAND_MEMBER").GET().timeout(Duration.ofSeconds(10)).build();
    }

    static HttpRequest approve(Row r) {
        return brand(HttpRequest.newBuilder(URI.create(BASE + "/api/core/brand/jobs/" + r.job()
                + "/swap-offer/approve")), r, "BRAND_ADMIN").POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10)).build();
    }

    static HttpRequest.Builder brand(HttpRequest.Builder b, Row r, String role) {
        return b.header("X-Dev-Role", role).header("X-Dev-User-Id", UUID.randomUUID().toString())
                .header("X-Dev-Org-Id", r.brand());
    }

    interface Req {
        HttpRequest build(Row r);
    }

    static boolean run(String name, int okStatus, int rps, List<Row> rows, HttpClient http, Req req, long gateMs)
            throws Exception {
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        long interval = 1_000_000_000L / rps;
        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < rows.size(); i++) {
                long wait = start + i * interval - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                Row row = rows.get(i);
                pool.submit(() -> {
                    long t0 = System.nanoTime();
                    String status;
                    try {
                        status = String.valueOf(http.send(req.build(row), HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                    } catch (Exception e) {
                        status = e.getClass().getSimpleName();
                    }
                    lat.add((System.nanoTime() - t0) / 1_000_000);
                    statuses.computeIfAbsent(status, k -> new LongAdder()).increment();
                });
            }
        }
        List<Long> s = new ArrayList<>(lat);
        Collections.sort(s);
        long failed = statuses.entrySet().stream().filter(e -> !e.getKey().equals(String.valueOf(okStatus)))
                .mapToLong(e -> e.getValue().sum()).sum();
        long p95 = pct(s, 95);
        boolean pass = p95 <= gateMs && failed == 0;
        System.out.printf("%-10s n=%5d at %d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  %s%n", name, s.size(),
                rps, pct(s, 50), p95, pct(s, 99), s.getLast(), statuses,
                gateMs == Long.MAX_VALUE ? "(warm-up, not gated)" : "GATE p95<=" + gateMs + "ms " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
