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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Open-loop load test: fires requests at a fixed rate (not "as fast as responses come back"), so a
 * slow server shows up as latency instead of being hidden. Mix: 60% job detail, 25% job list
 * (varied filters), 15% transitions (OPS_EXTEND with an Idempotency-Key).
 *
 * Usage: java LoadTest.java <rps> <seconds> <jobIdsFile> [listShare]
 * Exits 1 unless p95 <= 300 ms and every request got 200 (the gate).
 */
public class LoadTest {

    static final String BASE = "http://localhost:8081";
    static final String OPS = "0195f000-0000-7000-8000-000000000001";

    public static void main(String[] args) throws Exception {
        int rps = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        double listShare = args.length > 3 ? Double.parseDouble(args[3]) : 0.25;
        List<String> jobs = Files.readAllLines(Path.of(args[2])).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger next = new AtomicInteger();

        long intervalNanos = 1_000_000_000L / rps;
        long start = System.nanoTime();
        long total = (long) rps * seconds;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long i = 0; i < total; i++) {
                long due = start + i * intervalNanos;
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                pool.submit(() -> {
                    inFlight.incrementAndGet();
                    double r = ThreadLocalRandom.current().nextDouble();
                    String kind;
                    HttpRequest req;
                    String job = jobs.get(Math.floorMod(next.getAndIncrement(), jobs.size()));
                    if (r < 0.85 - listShare) {
                        kind = "GET detail";
                        req = ops(HttpRequest.newBuilder(URI.create(BASE + "/api/core/ops/jobs/" + job))).GET().build();
                    } else if (r < 0.85) {
                        kind = "GET list";
                        String[] filters = {"", "?status=IN_PRODUCTION", "?overdue=true", "?page=200",
                                "?brandOrgId=00000000-0000-4000-8000-000000000042"};
                        String f = filters[ThreadLocalRandom.current().nextInt(filters.length)];
                        req = ops(HttpRequest.newBuilder(URI.create(BASE + "/api/core/ops/jobs" + f))).GET().build();
                    } else {
                        kind = "POST transition";
                        req = ops(HttpRequest.newBuilder(URI.create(BASE + "/api/core/jobs/" + job + "/transitions")))
                                .header("Content-Type", "application/json")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"OPS_EXTEND\",\"reason\":\"load test\"}"))
                                .build();
                    }
                    long t0 = System.nanoTime();
                    String status;
                    try {
                        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                        status = String.valueOf(resp.statusCode());
                    } catch (Exception e) {
                        status = e.getClass().getSimpleName();
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    latencies.computeIfAbsent(kind, k -> Collections.synchronizedList(new ArrayList<>())).add(ms);
                    statuses.computeIfAbsent(kind + " " + status, k -> new LongAdder()).increment();
                    inFlight.decrementAndGet();
                });
            }
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        System.out.printf("target %d rps for %ds, sent %d in %.1fs (%.0f rps achieved)%n", rps, seconds, total, elapsed,
                total / elapsed);
        List<Long> all = new ArrayList<>();
        latencies.forEach((k, v) -> {
            List<Long> s = new ArrayList<>(v);
            all.addAll(s);
            Collections.sort(s);
            System.out.printf("%-16s n=%6d  p50=%4dms  p95=%4dms  p99=%4dms  max=%5dms%n", k, s.size(), pct(s, 50),
                    pct(s, 95), pct(s, 99), s.getLast());
        });
        Collections.sort(all);
        System.out.printf("%-16s n=%6d  p50=%4dms  p95=%4dms  p99=%4dms  max=%5dms%n", "ALL", all.size(), pct(all, 50),
                pct(all, 95), pct(all, 99), all.getLast());
        statuses.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue().sum()));

        // Gate (docs/features/f5-job-engine.md, target): p95 under P95_LIMIT_MS and no failed request.
        long failed = statuses.entrySet().stream()
                .filter(e -> !e.getKey().endsWith(" 200")).mapToLong(e -> e.getValue().sum()).sum();
        long p95 = pct(all, 95);
        boolean pass = p95 <= P95_LIMIT_MS && failed == 0;
        System.out.printf("GATE %s: p95=%dms (limit %dms), failed=%d (limit 0)%n", pass ? "PASS" : "FAIL", p95,
                P95_LIMIT_MS, failed);
        System.exit(pass ? 0 : 1);
    }

    static final long P95_LIMIT_MS = 300;

    static HttpRequest.Builder ops(HttpRequest.Builder b) {
        return b.timeout(Duration.ofSeconds(10)).header("X-Dev-Role", "OPS").header("X-Dev-User-Id", OPS);
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
