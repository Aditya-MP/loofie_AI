import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F1 round A load. Open loop at a fixed rate per phase.
 *
 * Auth cost: the same creator endpoint (GET /api/core/notifications) at {authRps}, first identified by dev headers (no
 * login work), then by real session cookies (session lookup + joined account on every request). The difference in p95
 * is what login adds to every request. Gate: <= 5 ms.
 *
 * OTP: {otpRps} sends to new phones, then the verifies (codes read from the app log written by the fake sender), then
 * the signups (account + consents + session). Gate: p95 <= 300 ms, every answer the expected status.
 *
 * Usage: java AuthLoadTest.java <authRps> <authRequests> <sessionCount> <otpRps> <otpCount> <app log file>
 * Sessions must exist with cookie values "perf-session-<i>-padpadpad" for i in 1..sessionCount (see auth-seed.sql).
 */
public class AuthLoadTest {

    static final String BASE = "http://localhost:8081";
    static final String COOKIE = "loofie_session";
    static final Pattern FAKE_SMS = Pattern.compile("\\[fake PHONE] to \\+91\\*{6}(\\d{4}) \\(OTP\\): (\\d{6})");

    public static void main(String[] args) throws Exception {
        int authRps = Integer.parseInt(args[0]);
        int authN = Integer.parseInt(args[1]);
        int sessions = Integer.parseInt(args[2]);
        int otpRps = Integer.parseInt(args[3]);
        int otpN = Integer.parseInt(args[4]);
        Path log = Path.of(args[5]);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

        List<UUID> creators = new ArrayList<>();
        for (int i = 1; i <= sessions; i++) {
            creators.add(UUID.nameUUIDFromBytes(("perf-creator-" + i).getBytes()));
        }
        boolean ok = true;
        long[] dev = phase("INBOX_DEV_HEADERS", authRps, authN, http, "200", i -> get("/api/core/notifications")
                .header("X-Dev-Role", "CREATOR").header("X-Dev-User-Id", creators.get(i % sessions).toString()).build());
        long[] cookie = phase("INBOX_SESSION", authRps, authN, http, "200", i -> get("/api/core/notifications")
                .header("Cookie", COOKIE + "=perf-session-" + (i % sessions + 1) + "-padpadpad").build());
        ok &= dev[1] >= 0 && cookie[1] >= 0;
        long added = cookie[0] - dev[0];
        System.out.printf("AUTH COST p95: session %d ms - dev headers %d ms = %d ms added  GATE %s%n", cookie[0],
                dev[0], added, added <= 5 ? "PASS" : "FAIL");
        ok &= added <= 5;
        ok &= phase("ME_SESSION", authRps, authN, http, "200", i -> get("/api/core/auth/me")
                .header("Cookie", COOKIE + "=perf-session-" + (i % sessions + 1) + "-padpadpad").build())[1] >= 0;

        // Phones with distinct last 4 digits, so each code in the (masked) log maps to one phone.
        int base = (int) (System.currentTimeMillis() % 9000);
        List<String> phones = new ArrayList<>();
        for (int i = 0; i < otpN; i++) {
            phones.add("+9196" + String.format("%04d", base % 10000) + String.format("%04d", (base + i) % 10000));
        }
        Map<String, String> challenge = new ConcurrentHashMap<>();
        ok &= phase("OTP_SEND", otpRps, otpN, http, "202", i -> json("/api/core/auth/otp/send",
                "{\"channel\":\"PHONE\",\"destination\":\"" + phones.get(i) + "\"}"), (i, body) -> challenge.put(
                phones.get(i), body.replaceAll(".*\"challengeId\":\"([^\"]+)\".*", "$1")))[1] >= 0;
        Thread.sleep(1500); // let the log flush
        Map<String, String> codes = codesFromLog(log);
        ok &= phase("OTP_VERIFY", otpRps, otpN, http, "200", i -> json("/api/core/auth/otp/verify",
                "{\"challengeId\":\"" + challenge.get(phones.get(i)) + "\",\"code\":\"" + codes.get(last4(phones.get(i)))
                        + "\"}"))[1] >= 0;
        ok &= phase("SIGNUP", otpRps, otpN, http, "200", i -> json("/api/core/auth/signup",
                "{\"challengeId\":\"" + challenge.get(phones.get(i)) + "\",\"accountType\":\"CREATOR\","
                        + "\"acceptTerms\":true,\"confirmAdult\":true}"))[1] >= 0;
        System.exit(ok ? 0 : 1);
    }

    static String last4(String phone) {
        return phone.substring(phone.length() - 4);
    }

    static Map<String, String> codesFromLog(Path log) throws Exception {
        Map<String, String> codes = new HashMap<>();
        for (String line : Files.readAllLines(log)) {
            Matcher m = FAKE_SMS.matcher(line);
            if (m.find()) {
                codes.put(m.group(1), m.group(2));
            }
        }
        return codes;
    }

    static HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path)).GET().timeout(Duration.ofSeconds(10));
    }

    static HttpRequest json(String path, String body) {
        return HttpRequest.newBuilder(URI.create(BASE + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(10)).build();
    }

    interface OnBody {
        void accept(int i, String body);
    }

    static long[] phase(String name, int rps, int n, HttpClient http, String expect, IntFunction<HttpRequest> req)
            throws Exception {
        return phase(name, rps, n, http, expect, req, null);
    }

    /** @return {p95 ms, 0 if every answer was the expected status else -1} */
    static long[] phase(String name, int rps, int n, HttpClient http, String expect, IntFunction<HttpRequest> req,
                        OnBody onBody) throws Exception {
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
                        HttpResponse<String> r = http.send(req.apply(k), HttpResponse.BodyHandlers.ofString());
                        status = String.valueOf(r.statusCode());
                        if (onBody != null) {
                            onBody.accept(k, r.body());
                        }
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
        long wrong = statuses.entrySet().stream().filter(e -> !e.getKey().equals(expect))
                .mapToLong(e -> e.getValue().sum()).sum();
        long p95 = pct(s, 95);
        boolean pass = wrong == 0 && (name.startsWith("INBOX") || name.startsWith("ME") || p95 <= 300);
        System.out.printf("%-18s n=%5d at %3d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  %s%n", name, s.size(),
                rps, pct(s, 50), p95, pct(s, 99), s.getLast(), statuses, pass ? "PASS" : "FAIL");
        return new long[] {p95, pass ? 0 : -1};
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
