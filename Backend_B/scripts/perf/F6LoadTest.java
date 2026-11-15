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
 * F6 load: real delivery actions, not reads. Phase 1 the brand marks jobs shipped (holiday lookup, shipment
 * insert, clocks); phase 2 the creators confirm receipt with a photo (shipment update, follow-up to production).
 * Open loop at a fixed rate. Exits 1 unless p95 <= 300 ms and every request got 200.
 *
 * Usage: java F6LoadTest.java <rps> <jobsPerPhase> <file of "jobId,brandOrgId,creatorId" for CREATOR_ACCEPTED jobs>
 */
public class F6LoadTest {

    static final String BASE = "http://localhost:8081";
    static final long P95_LIMIT_MS = 300;

    record Job(String id, String brand, String creator) {
    }

    public static void main(String[] args) throws Exception {
        int rps = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]);
        List<Job> jobs = Files.readAllLines(Path.of(args[2])).stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(l -> l.split(",")).map(p -> new Job(p[0], p[1], p[2])).limit(n).toList();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

        boolean ship = phase("MARK_SHIPPED", rps, jobs, http, j -> HttpRequest.newBuilder(transitions(j))
                .header("X-Dev-Role", "BRAND_ADMIN").header("X-Dev-User-Id", UUID.randomUUID().toString())
                .header("X-Dev-Org-Id", j.brand()).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"action\":\"MARK_SHIPPED\",\"courier\":\"Delhivery\",\"trackingNumber\":\"DL" + j.id().substring(0, 8) + "\"}"))
                .timeout(Duration.ofSeconds(10)).build());
        boolean receive = phase("CONFIRM_RECEIVED", rps, jobs, http, j -> HttpRequest.newBuilder(transitions(j))
                .header("X-Dev-Role", "CREATOR").header("X-Dev-User-Id", j.creator())
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"action\":\"CONFIRM_RECEIVED\",\"photoRef\":\"receipts/" + UUID.randomUUID() + ".jpg\"}"))
                .timeout(Duration.ofSeconds(10)).build());
        System.exit(ship && receive ? 0 : 1);
    }

    static URI transitions(Job j) {
        return URI.create(BASE + "/api/core/jobs/" + j.id() + "/transitions");
    }

    interface Req {
        HttpRequest build(Job j);
    }

    static boolean phase(String name, int rps, List<Job> jobs, HttpClient http, Req req) throws Exception {
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        long interval = 1_000_000_000L / rps;
        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < jobs.size(); i++) {
                long wait = start + i * interval - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                Job j = jobs.get(i);
                pool.submit(() -> {
                    long t0 = System.nanoTime();
                    String status;
                    try {
                        status = String.valueOf(http.send(req.build(j), HttpResponse.BodyHandlers.discarding()).statusCode());
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
        long failed = statuses.entrySet().stream().filter(e -> !e.getKey().equals("200")).mapToLong(e -> e.getValue().sum()).sum();
        long p95 = pct(s, 95);
        boolean pass = p95 <= P95_LIMIT_MS && failed == 0;
        System.out.printf("%-17s n=%5d at %d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  GATE %s%n", name, s.size(),
                rps, pct(s, 50), p95, pct(s, 99), s.getLast(), statuses, pass ? "PASS" : "FAIL");
        return pass;
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
