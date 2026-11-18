import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * F1 round C part 2 load: account deletions of creators with 20 sessions and 3 screenshots each (deletion-seed.sql),
 * through the ops endpoint (dev headers, OPS_LEAD) so no codes are needed; the deletion transaction is the same one a
 * creator's own confirmed deletion runs. Gate: p95 <= 200 ms, every answer 204.
 *
 * Usage: java DeletionLoadTest.java <rps> <from> <to>
 */
public class DeletionLoadTest {

    public static void main(String[] args) throws Exception {
        int rps = Integer.parseInt(args[0]);
        int from = Integer.parseInt(args[1]);
        int to = Integer.parseInt(args[2]);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        long interval = 1_000_000_000L / rps;
        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = from; i <= to; i++) {
                long wait = start + (i - from) * interval - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                UUID creator = md5Uuid("perf-creator-" + i);
                pool.submit(() -> {
                    long t0 = System.nanoTime();
                    String status;
                    try {
                        status = String.valueOf(http.send(HttpRequest.newBuilder(URI.create(
                                        "http://localhost:8081/api/core/ops/accounts/" + creator + "/delete"))
                                .header("X-Dev-Role", "OPS_LEAD").header("X-Dev-User-Id", UUID.randomUUID().toString())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"load test\"}"))
                                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                    } catch (Exception e) {
                        status = e.getClass().getSimpleName();
                    }
                    lat.add((System.nanoTime() - t0) / 1_000_000);
                    statuses.computeIfAbsent(status, s -> new LongAdder()).increment();
                });
            }
        }
        List<Long> s = new ArrayList<>(lat);
        Collections.sort(s);
        long wrong = statuses.entrySet().stream().filter(e -> !e.getKey().equals("204"))
                .mapToLong(e -> e.getValue().sum()).sum();
        long p95 = pct(s, 95);
        boolean pass = wrong == 0 && p95 <= 200;
        System.out.printf("DELETE_ACCOUNT     n=%5d at %3d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  GATE %s%n",
                s.size(), rps, pct(s, 50), p95, pct(s, 99), s.getLast(), statuses, pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** Postgres md5(text)::uuid. */
    static UUID md5Uuid(String v) throws Exception {
        String hex = String.format("%032x", new BigInteger(1, MessageDigest.getInstance("MD5")
                .digest(v.getBytes(StandardCharsets.UTF_8))));
        return UUID.fromString(hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
