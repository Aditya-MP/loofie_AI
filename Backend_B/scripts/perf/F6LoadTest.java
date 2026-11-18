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
 * F6 load: the whole delivery flow, each phase open loop at a fixed rate. The brand ships every job (courier lookup,
 * duplicate check, shipment, clocks, creator notice); then half the creators confirm receipt (production starts,
 * brand notice), a fifth report "not received" (brand + ops notices), a fifth get courier proof from ops (creator
 * notice), a tenth confirm and then report damage (clock paused); the brands reship the "not received" ones; finally
 * every brand opens the delivery screen. Exits 1 unless every phase has p95 <= 300 ms and every request got 200.
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

        // Groups by position: 0-4 receive, 5-6 not received, 7-8 courier proof, 9 receive then damage.
        List<Job> receive = new ArrayList<>();
        List<Job> missing = new ArrayList<>();
        List<Job> proof = new ArrayList<>();
        List<Job> damaged = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            int g = i % 10;
            (g < 5 ? receive : g < 7 ? missing : g < 9 ? proof : damaged).add(jobs.get(i));
        }
        List<Job> receiveAll = new ArrayList<>(receive);
        receiveAll.addAll(damaged);

        // F7 D8: photos are real uploads on the register, issued to the creator (prepared outside the measured phases).
        Map<String, String> receipts = new ConcurrentHashMap<>();
        Map<String, String> damage = new ConcurrentHashMap<>();
        try (var prep = Executors.newFixedThreadPool(16)) {
            for (Job j : receiveAll) {
                prep.submit(() -> receipts.put(j.id(), uploadPhoto(http, j, "RECEIPT_PHOTO")));
            }
            for (Job j : damaged) {
                prep.submit(() -> damage.put(j.id(), uploadPhoto(http, j, "DAMAGE_EVIDENCE")));
            }
        }

        boolean ok = phase("MARK_SHIPPED", rps, jobs, http, j -> post(j, brand(j),
                "{\"action\":\"MARK_SHIPPED\",\"courierCode\":\"DELHIVERY\",\"trackingNumber\":\"DL" + j.id().replace("-", "") + "\"}"));
        ok &= phase("CONFIRM_RECEIVED", rps, receiveAll, http, j -> post(j, creator(j),
                "{\"action\":\"CONFIRM_RECEIVED\",\"photoRef\":\"" + receipts.get(j.id()) + "\"}"));
        ok &= phase("REPORT_NOT_RECEIVED", rps, missing, http, j -> post(j, creator(j),
                "{\"action\":\"REPORT_DELIVERY_ISSUE\",\"issueType\":\"NOT_RECEIVED\"}"));
        ok &= phase("COURIER_DELIVERED", rps, proof, http, j -> post(j, ops(),
                "{\"action\":\"COURIER_DELIVERED\",\"reason\":\"tracking page\",\"deliverySource\":\"TRACKING_PAGE\"}"));
        ok &= phase("REPORT_DAMAGE", rps, damaged, http, j -> post(j, creator(j),
                "{\"action\":\"REPORT_DELIVERY_ISSUE\",\"issueType\":\"DAMAGED\",\"evidenceRef\":\"" + damage.get(j.id()) + "\"}"));
        ok &= phase("RESHIP", rps, missing, http, j -> post(j, brand(j),
                "{\"action\":\"RESHIP\",\"courierCode\":\"BLUE_DART\",\"trackingNumber\":\"BD" + j.id().replace("-", "") + "\"}"));
        ok &= phase("GET_DELIVERY_VIEW", rps, jobs, http, j -> brand(j).apply(HttpRequest.newBuilder(
                URI.create(BASE + "/api/core/jobs/" + j.id() + "/delivery"))).GET().timeout(Duration.ofSeconds(10)).build());
        System.exit(ok ? 0 : 1);
    }

    interface Who {
        HttpRequest.Builder apply(HttpRequest.Builder b);
    }

    static Who brand(Job j) {
        return b -> b.header("X-Dev-Role", "BRAND_ADMIN").header("X-Dev-User-Id", UUID.randomUUID().toString())
                .header("X-Dev-Org-Id", j.brand());
    }

    static Who creator(Job j) {
        return b -> b.header("X-Dev-Role", "CREATOR").header("X-Dev-User-Id", j.creator());
    }

    static Who ops() {
        return b -> b.header("X-Dev-Role", "OPS").header("X-Dev-User-Id", UUID.randomUUID().toString());
    }

    /** An upload link from the app, then the file straight to storage; returns the key. */
    static String uploadPhoto(HttpClient http, Job j, String purpose) {
        try {
            HttpResponse<String> t = http.send(creator(j).apply(HttpRequest.newBuilder(URI.create(BASE
                            + "/api/core/jobs/" + j.id() + "/uploads"))).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"purpose\":\"" + purpose
                            + "\",\"contentType\":\"image/jpeg\"}")).build(), HttpResponse.BodyHandlers.ofString());
            String key = field(t.body(), "key");
            http.send(HttpRequest.newBuilder(URI.create(field(t.body(), "url"))).header("Content-Type", "image/jpeg")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[4096])).build(),
                    HttpResponse.BodyHandlers.discarding());
            return key;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** One string field of a flat JSON answer (no JSON library in a single-file script). */
    static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\":\"") + name.length() + 4;
        return json.substring(i, json.indexOf('"', i)).replace("\\u0026", "&");
    }

    static HttpRequest post(Job j, Who who, String body) {
        return who.apply(HttpRequest.newBuilder(transitions(j))).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10)).build();
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
