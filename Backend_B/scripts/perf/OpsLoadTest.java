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
 * F10 ops console under load, against a running core-service (local profile, dev headers) on load-data.sql +
 * ops-seed.sql (1M jobs, 100k creators, 200k time entries). Open loop at {rps}, {n} calls per phase after a warm-up
 * of {warm} calls per phase: creator search (read), overview (read), payout queue (read), time log (write).
 * Gates: reads p95 <= 50 ms, writes <= 300 ms, no errors. Exit 1 on any failure.
 *
 * Usage: java OpsLoadTest.java <rps> <n> <warm> <ops-jobs.csv (job_id,order_id)>
 */
public class OpsLoadTest {

    static final String BASE = "http://localhost:8081";
    static final String OPS = UUID.randomUUID().toString();
    static final String[] CITIES = {"Mumbai", "Delhi", "Bengaluru", "Pune", "Jaipur", "Kolkata", "Chennai", "Hyderabad"};
    static final String[] CATEGORIES = {"beauty_skincare", "personal_care", "other"};

    interface Req {
        HttpRequest build(int i);
    }

    public static void main(String[] args) throws Exception {
        int rps = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]);
        int warm = Integer.parseInt(args[2]);
        List<String> jobs = Files.readAllLines(Path.of(args[3])).stream().filter(s -> !s.isBlank())
                .map(l -> l.split(",")[0]).toList();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

        Req searchReq = i -> get("/api/core/ops/creators?city=" + CITIES[i % CITIES.length] + "&category="
                + CATEGORIES[i % CATEGORIES.length] + "&maxRate=" + (2000 + (i % 10) * 1500)
                + (i % 3 == 0 ? "&level=VERIFIED" : ""));
        Req overviewReq = i -> get("/api/core/ops/overview");
        Req payoutsReq = i -> get("/api/core/ops/payouts?size=50");
        Req timeReq = i -> HttpRequest.newBuilder(URI.create(BASE + "/api/core/ops/time"))
                .header("X-Dev-Role", "OPS").header("X-Dev-User-Id", OPS).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jobId\":\"" + jobs.get(i % jobs.size())
                        + "\",\"minutes\":15,\"activity\":\"COORDINATION\"}")).timeout(Duration.ofSeconds(10)).build();

        for (Req r : List.of(searchReq, overviewReq, payoutsReq, timeReq)) {
            run("warm-up", r == timeReq ? 201 : 200, rps, warm, http, r, Long.MAX_VALUE);
        }
        boolean ok = run("SEARCH", 200, rps, n, http, searchReq, 50);
        ok &= run("OVERVIEW", 200, rps, n, http, overviewReq, 50);
        ok &= run("PAYOUTS", 200, rps, n, http, payoutsReq, 50);
        ok &= run("TIME_LOG", 201, rps, n, http, timeReq, 300);
        // Search again last: the first measured phase follows the warm-up, whose backlog may still be draining.
        run("SEARCH-2", 200, rps, n, http, searchReq, Long.MAX_VALUE);
        System.exit(ok ? 0 : 1);
    }

    static HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path)).header("X-Dev-Role", "OPS").header("X-Dev-User-Id", OPS)
                .GET().timeout(Duration.ofSeconds(10)).build();
    }

    static boolean run(String name, int okStatus, int rps, int n, HttpClient http, Req req, long gateMs)
            throws Exception {
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        long interval = 1_000_000_000L / rps;
        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                long wait = start + i * interval - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                int k = i;
                pool.submit(() -> {
                    long t0 = System.nanoTime();
                    String status;
                    try {
                        status = String.valueOf(http.send(req.build(k), HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                    } catch (Exception e) {
                        status = e.getClass().getSimpleName();
                    }
                    lat.add((System.nanoTime() - t0) / 1_000_000);
                    statuses.computeIfAbsent(status, x -> new LongAdder()).increment();
                });
            }
        }
        List<Long> s = new ArrayList<>(lat);
        Collections.sort(s);
        long failed = statuses.entrySet().stream().filter(e -> !e.getKey().equals(String.valueOf(okStatus)))
                .mapToLong(e -> e.getValue().sum()).sum();
        long p95 = pct(s, 95);
        boolean pass = p95 <= gateMs && failed == 0;
        System.out.printf("%-9s n=%5d at %d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  %s%n", name, s.size(), rps,
                pct(s, 50), p95, pct(s, 99), s.getLast(), statuses,
                gateMs == Long.MAX_VALUE ? "(not gated)" : "GATE p95<=" + gateMs + "ms " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
