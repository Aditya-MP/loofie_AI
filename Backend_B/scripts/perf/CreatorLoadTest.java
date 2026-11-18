import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;

/**
 * F1 round C load (creator-seed.sql data, FakeBackendA.java on :8100, app with AI_FEEDBACK_URL pointing at it):
 *
 * 1. PROFILE_READ: creators read their passport (session cookie) at {rps}. Gate: p95 <= 20 ms, every answer 200.
 * 2. BACKEND_A_DIRECT then IG_POLL: the Instagram status poll through Backend B vs. the fake Backend A called
 *    directly, at {pollRps}. Gate: Backend B adds <= 10 ms at p95, every answer 200.
 *
 * Usage: java CreatorLoadTest.java <rps> <reads> <creators> <pollRps> <polls> <pollFrom>
 */
public class CreatorLoadTest {

    static final String BASE = "http://localhost:8081";
    static final String FAKE_A = "http://localhost:8100";
    static final String COOKIE = "loofie_session";

    public static void main(String[] args) throws Exception {
        int rps = Integer.parseInt(args[0]);
        int reads = Integer.parseInt(args[1]);
        int creators = Integer.parseInt(args[2]);
        int pollRps = Integer.parseInt(args[3]);
        int polls = Integer.parseInt(args[4]);
        int pollFrom = Integer.parseInt(args[5]);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1)
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        boolean ok = true;
        if (reads > 0) {
            long[] r = phase("PROFILE_READ", rps, reads, http, i -> get(BASE + "/api/core/creator/profile")
                    .header("Cookie", COOKIE + "=" + token(i % creators + 1)).build());
            boolean pass = r[1] == 0 && r[0] <= 20;
            System.out.printf("PROFILE READ p95 %d ms (gate <= 20 ms)  GATE %s%n", r[0], pass ? "PASS" : "FAIL");
            ok &= pass;
        }
        if (polls > 0) {
            long[] direct = phase("BACKEND_A_DIRECT", pollRps, polls, http, i -> get(FAKE_A
                    + "/ai/instagram/accounts/x" + i).header("X-API-Key", "perf-key").build());
            long[] poll = phase("IG_POLL", pollRps, polls, http, i -> get(BASE + "/api/core/creator/instagram")
                    .header("Cookie", COOKIE + "=" + token(pollFrom + i)).build());
            long added = poll[0] - direct[0];
            boolean pass = poll[1] == 0 && direct[1] == 0 && added <= 10;
            System.out.printf("IG POLL ADDED p95: %d ms - %d ms = %d ms (gate <= 10 ms)  GATE %s%n", poll[0],
                    direct[0], added, pass ? "PASS" : "FAIL");
            ok &= pass;
        }
        System.exit(ok ? 0 : 1);
    }

    static String token(int i) {
        return "perf-creator-session-" + i + "-padpad";
    }

    static HttpRequest.Builder get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10));
    }

    /** @return {p95, 0 = all 200 / -1 = not} */
    static long[] phase(String name, int rps, int n, HttpClient http, IntFunction<HttpRequest> req) throws Exception {
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
                        status = String.valueOf(http.send(req.apply(k), HttpResponse.BodyHandlers.discarding())
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
        long wrong = statuses.entrySet().stream().filter(e -> !e.getKey().equals("200"))
                .mapToLong(e -> e.getValue().sum()).sum();
        long p95 = pct(s, 95);
        System.out.printf("%-18s n=%5d at %3d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s%n", name, s.size(), rps,
                pct(s, 50), p95, pct(s, 99), s.getLast(), statuses);
        return new long[] {p95, wrong == 0 ? 0 : -1};
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
