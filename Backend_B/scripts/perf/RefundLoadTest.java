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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refund requests and approvals under load (money paths: price lookup, brand history count, job hold, then the
 * cancel transition on approval). Phase 1 an OPS_LEAD asks to cancel PAID jobs; phase 2 the same lead approves them
 * (allowed: nobody accepted, under Rs 10,000). Open loop. Exits 1 unless p95 <= 300 ms and every call succeeded.
 *
 * Usage: java RefundLoadTest.java <rps> <jobs> <file of PAID job ids>
 */
public class RefundLoadTest {

    static final String BASE = "http://localhost:8081";
    static final long P95_LIMIT_MS = 300;
    static final String LEAD = UUID.randomUUID().toString();
    static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    public static void main(String[] args) throws Exception {
        int rps = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]);
        List<String> jobs = Files.readAllLines(Path.of(args[2])).stream().map(String::trim).filter(s -> !s.isEmpty())
                .limit(n).toList();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        Map<String, String> requestIds = new ConcurrentHashMap<>();

        boolean ask = phase("REQUEST", 201, rps, jobs, http, job -> post(
                BASE + "/api/core/ops/jobs/" + job + "/refund-requests",
                "{\"action\":\"CANCEL\",\"reasonCode\":\"BRAND_CANCELLED\",\"note\":\"load test\"}"),
                (job, body) -> {
                    Matcher m = ID.matcher(body);
                    if (m.find()) {
                        requestIds.put(job, m.group(1));
                    }
                });
        List<String> asked = jobs.stream().filter(requestIds::containsKey).toList();
        boolean approve = phase("APPROVE", 200, rps, asked, http, job -> post(
                BASE + "/api/core/ops/refund-requests/" + requestIds.get(job) + "/approve", "{}"), (j, b) -> { });
        System.exit(ask && approve ? 0 : 1);
    }

    static HttpRequest post(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url)).header("X-Dev-Role", "OPS_LEAD").header("X-Dev-User-Id", LEAD)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(10)).build();
    }

    interface Req {
        HttpRequest build(String job);
    }

    interface OnBody {
        void accept(String job, String body);
    }

    static boolean phase(String name, int okStatus, int rps, List<String> jobs, HttpClient http, Req req, OnBody onBody)
            throws Exception {
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
                String job = jobs.get(i);
                pool.submit(() -> {
                    long t0 = System.nanoTime();
                    String status;
                    try {
                        HttpResponse<String> r = http.send(req.build(job), HttpResponse.BodyHandlers.ofString());
                        status = String.valueOf(r.statusCode());
                        onBody.accept(job, r.body());
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
        boolean pass = p95 <= P95_LIMIT_MS && failed == 0;
        System.out.printf("%-8s n=%5d at %d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  GATE %s%n", name, s.size(),
                rps, pct(s, 50), p95, pct(s, 99), s.getLast(), statuses, pass ? "PASS" : "FAIL");
        return pass;
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
