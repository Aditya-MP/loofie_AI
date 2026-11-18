import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * F1 round B load: brand users identified by real session cookies (membership read on every request).
 *
 * 1. Brand inbox at {rps}: dev headers, then session cookies. Gate: the p95 difference <= 5 ms.
 * 2. "Shipped" (MARK_SHIPPED) by the brand's admin with cookie + CSRF header, at {shipRps}: the full F6 path (courier
 *    lookup, duplicate check, shipment, clocks with the brand's pickup state, creator notice). Gate: p95 <= 300 ms,
 *    every answer 200. Jobs [from, from + n) of brand-seed.sql; run a warm-up on a different range first.
 *
 * The CSRF token is computed the way the app does it (HMAC of the session hash with the OTP pepper), so this only
 * works against an app using the public dev pepper (local profile, dev-secrets).
 *
 * Usage: java BrandLoadTest.java <rps> <inboxRequests> <orgs> <shipRps> <jobFrom> <jobCount>
 */
public class BrandLoadTest {

    static final String BASE = "http://localhost:8081";
    static final String COOKIE = "loofie_session";
    static final String DEV_PEPPER = "bG9vZmllLWRldi1vbmx5LXBlcHBlci1ub3QtZm9yLXByb2R1Y3Rpb24tdXNl";

    public static void main(String[] args) throws Exception {
        int rps = Integer.parseInt(args[0]);
        int inboxN = Integer.parseInt(args[1]);
        int orgs = Integer.parseInt(args[2]);
        int shipRps = Integer.parseInt(args[3]);
        int from = Integer.parseInt(args[4]);
        int jobs = Integer.parseInt(args[5]);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        boolean ok = true;
        if (inboxN > 0) {
            long[] dev = phase("BRAND_INBOX_DEV", rps, inboxN, http, i -> get("/api/core/notifications")
                    .header("X-Dev-Role", "BRAND_ADMIN").header("X-Dev-User-Id", user(i % orgs + 1).toString())
                    .header("X-Dev-Org-Id", org(i % orgs + 1).toString()).build(), false);
            long[] cookie = phase("BRAND_INBOX_SESSION", rps, inboxN, http, i -> get("/api/core/notifications")
                    .header("Cookie", COOKIE + "=" + token(i % orgs + 1)).build(), false);
            long added = cookie[0] - dev[0];
            System.out.printf("BRAND AUTH COST p95: session %d ms - dev headers %d ms = %d ms added  GATE %s%n",
                    cookie[0], dev[0], added, added <= 5 ? "PASS" : "FAIL");
            ok &= added <= 5 && dev[1] == 0 && cookie[1] == 0;
        }
        if (jobs > 0) {
            ok &= phase("SHIP_BRAND_SESSION", shipRps, jobs, http, i -> {
                int j = from + i;
                int o = j % orgs + 1;
                UUID job = md5Uuid("perf-job-" + j);
                return HttpRequest.newBuilder(URI.create(BASE + "/api/core/jobs/" + job + "/transitions"))
                        .header("Cookie", COOKIE + "=" + token(o)).header("X-CSRF-Token", csrf(token(o)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"MARK_SHIPPED\",\"courierCode\":"
                                + "\"DELHIVERY\",\"trackingNumber\":\"DL" + job.toString().replace("-", "") + "\"}"))
                        .timeout(Duration.ofSeconds(10)).build();
            }, true)[1] == 0;
        }
        System.exit(ok ? 0 : 1);
    }

    static String token(int i) {
        return "perf-brand-session-" + i + "-padpadpad";
    }

    static UUID user(int i) {
        return md5Uuid("perf-brand-" + i);
    }

    static UUID org(int i) {
        return md5Uuid("perf-org-" + i);
    }

    /** Postgres md5(text)::uuid: the raw MD5 as a UUID, no version bits. */
    static UUID md5Uuid(String s) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
            return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                    + hex.substring(16, 20) + "-" + hex.substring(20));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String csrf(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(DEV_PEPPER), "HmacSHA256"));
            byte[] h = mac.doFinal(("csrf|" + HexFormat.of().formatHex(hash)).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path)).GET().timeout(Duration.ofSeconds(10));
    }

    /** @return {p95, 0 = pass / -1 = fail} */
    static long[] phase(String name, int rps, int n, HttpClient http, IntFunction<HttpRequest> req, boolean gate300)
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
        boolean pass = wrong == 0 && (!gate300 || p95 <= 300);
        System.out.printf("%-20s n=%5d at %3d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  %s%n", name, s.size(),
                rps, pct(s, 50), p95, pct(s, 99), s.getLast(), statuses, pass ? "PASS" : "FAIL");
        return new long[] {p95, pass ? 0 : -1};
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
