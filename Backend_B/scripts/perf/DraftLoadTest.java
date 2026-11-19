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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * F7 load test against a running core-service (local profile, dev headers), jobs from draft-seed.sql.
 *
 * <pre>
 * java DraftLoadTest.java endpoints jobs.csv tiny.mp4 100
 *     upload links + uploads (not measured), then at 100 rps: SUBMIT, BRAND_VIEW, REVIEW (approve), CREATOR_VIEW.
 *     Gate: p95 <= 50 ms, no errors.
 * java DraftLoadTest.java processing jobs.csv real60.mp4 100 20
 *     20 realistic videos submitted at once; the brand view is read at 100 rps before (baseline) and while the
 *     worker transcodes. Gate: all 20 READY, none FAILED, view p95 <= 50 ms while transcoding.
 * </pre>
 * jobs.csv: job_id,brand_org_id,creator_id,status,ready_draft_id (psql -At -F, output).
 */
public class DraftLoadTest {

    static final String BASE = "http://localhost:8081";
    /** Screens (reads). */
    static final long P95_LIMIT_MS = 50;
    /** Writes (submit, review): the gate every write endpoint uses (F5, F6, refunds), agreed 2026-11-18. */
    static final long WRITE_P95_LIMIT_MS = 300;

    record Job(String id, String brand, String creator, String status, String draft) {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        List<Job> jobs = Files.readAllLines(Path.of(args[1])).stream().filter(l -> !l.isBlank()).map(l -> l.split(",", -1))
                .map(p -> new Job(p[0], p[1], p[2], p[3], p.length > 4 ? p[4] : "")).toList();
        byte[] video = Files.readAllBytes(Path.of(args[2]));
        int rps = Integer.parseInt(args[3]);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        List<Job> producing = jobs.stream().filter(j -> j.status().equals("IN_PRODUCTION")).toList();
        List<Job> reviewing = jobs.stream().filter(j -> j.status().equals("IN_REVIEW")).toList();
        if (mode.equals("baseline")) { // diagnosis: warm-up + brand view only, no uploads
            List<Job> readers = new ArrayList<>();
            while (readers.size() < rps * 30) {
                readers.addAll(reviewing);
            }
            phase("VIEW_WARMUP", 50, readers.subList(0, 1500), http, j -> brand(j, HttpRequest.newBuilder(URI.create(
                    BASE + "/api/core/brand/jobs/" + j.id() + "/draft"))).GET().build(), 200);
            boolean ok = phase("VIEW_BASELINE", rps, readers.subList(0, rps * 30), http, j -> brand(j,
                    HttpRequest.newBuilder(URI.create(BASE + "/api/core/brand/jobs/" + j.id() + "/draft"))).GET().build(), 200);
            System.exit(ok ? 0 : 1);
        }
        boolean ok = mode.equals("endpoints") ? endpoints(http, producing, reviewing, video, rps)
                : processing(http, producing.subList(0, Integer.parseInt(args[4])), reviewing, video, rps);
        System.out.println(ok ? "ALL GATES PASS" : "GATE FAILED");
        System.exit(ok ? 0 : 1);
    }

    static boolean endpoints(HttpClient http, List<Job> producing, List<Job> reviewing, byte[] video, int rps)
            throws Exception {
        Map<String, String> keys = uploadAll(http, producing, video);
        System.out.printf("prepared %d uploads%n", keys.size());
        boolean ok = phase("SUBMIT", rps, producing, http, j -> creator(j, HttpRequest.newBuilder(URI.create(
                BASE + "/api/core/creator/jobs/" + j.id() + "/drafts"))).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"" + keys.get(j.id()) + "\"}")).build(), 202);
        ok &= phase("BRAND_VIEW", rps, reviewing, http, j -> brand(j, HttpRequest.newBuilder(URI.create(
                BASE + "/api/core/brand/jobs/" + j.id() + "/draft"))).GET().build(), 200);
        ok &= phase("REVIEW_APPROVE", rps, reviewing, http, j -> brand(j, HttpRequest.newBuilder(URI.create(
                BASE + "/api/core/brand/jobs/" + j.id() + "/draft/review"))).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(approveAll(j.draft()))).build(), 200);
        ok &= phase("CREATOR_VIEW", rps, producing, http, j -> creator(j, HttpRequest.newBuilder(URI.create(
                BASE + "/api/core/creator/jobs/" + j.id() + "/drafts"))).GET().build(), 200);
        return ok;
    }

    static boolean processing(HttpClient http, List<Job> batch, List<Job> reviewing, byte[] video, int rps)
            throws Exception {
        Map<String, String> keys = uploadAll(http, batch, video);
        System.out.printf("prepared %d uploads of %.1f MB%n", keys.size(), video.length / 1048576.0);
        // Let the machine settle after writing ~850 MB (on Windows the antivirus scans every new file).
        Thread.sleep(60_000);
        List<Job> readers = new ArrayList<>();
        while (readers.size() < rps * 30) {
            readers.addAll(reviewing);
        }
        // Warm-up, not gated (the app's code is cold right after a start; earlier rounds measure the same way).
        phase("VIEW_WARMUP", 50, readers.subList(0, 1500), http, j -> brand(j, HttpRequest.newBuilder(URI.create(
                BASE + "/api/core/brand/jobs/" + j.id() + "/draft"))).GET().build(), 200);
        Thread.sleep(5000);
        boolean ok = phase("VIEW_BASELINE", rps, readers.subList(0, rps * 30), http, j -> brand(j,
                HttpRequest.newBuilder(URI.create(BASE + "/api/core/brand/jobs/" + j.id() + "/draft"))).GET().build(), 200);

        long t0 = System.nanoTime();
        ok &= phase("SUBMIT_BURST", 1000, batch, http, j -> creator(j, HttpRequest.newBuilder(URI.create(
                BASE + "/api/core/creator/jobs/" + j.id() + "/drafts"))).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"" + keys.get(j.id()) + "\"}")).build(), 202);
        AtomicBoolean done = new AtomicBoolean();
        Map<String, String> finalStatus = new ConcurrentHashMap<>();
        Thread watcher = Thread.ofVirtual().start(() -> {
            while (!done.get()) {
                for (Job j : batch) {
                    if (finalStatus.containsKey(j.id())) {
                        continue;
                    }
                    try {
                        String body = http.send(creator(j, HttpRequest.newBuilder(URI.create(BASE + "/api/core/creator/jobs/"
                                + j.id() + "/drafts"))).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
                        int i = body.indexOf("\"status\":\"", body.indexOf("\"drafts\""));
                        String s = body.substring(i + 10, body.indexOf('"', i + 10));
                        if (s.equals("READY") || s.equals("FAILED") || s.equals("SUPERSEDED")) {
                            finalStatus.put(j.id(), s);
                            System.out.printf("  %5.1f s  %s %s%n", (System.nanoTime() - t0) / 1e9, j.id().substring(0, 8), s);
                        }
                    } catch (Exception e) {
                        // try again next round
                    }
                }
                if (finalStatus.size() == batch.size()) {
                    done.set(true);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        // Read at the same rate for as long as the worker is busy (in 10 s slices), capped at 15 minutes.
        List<Long> during = Collections.synchronizedList(new ArrayList<>());
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        while (!done.get() && System.nanoTime() < deadline) {
            measure(rps, readers.subList(0, rps * 10), http, j -> brand(j, HttpRequest.newBuilder(URI.create(
                    BASE + "/api/core/brand/jobs/" + j.id() + "/draft"))).GET().build(), during, statuses);
        }
        done.set(true);
        watcher.join();
        double secs = (System.nanoTime() - t0) / 1e9;
        List<Long> s = new ArrayList<>(during);
        Collections.sort(s);
        long failedReads = statuses.entrySet().stream().filter(e -> !e.getKey().equals("200"))
                .mapToLong(e -> e.getValue().sum()).sum();
        boolean viewOk = !s.isEmpty() && pct(s, 95) <= P95_LIMIT_MS && failedReads == 0;
        System.out.printf("%-17s n=%5d at %d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  GATE %s%n",
                "VIEW_DURING", s.size(), rps, pct(s, 50), pct(s, 95), pct(s, 99), s.isEmpty() ? 0 : s.getLast(), statuses,
                viewOk ? "PASS" : "FAIL");
        long ready = finalStatus.values().stream().filter("READY"::equals).count();
        boolean allReady = ready == batch.size();
        System.out.printf("PROCESSING        %d/%d READY in %.1f s (%.1f s per video)  %s  GATE %s%n", ready, batch.size(),
                secs, secs / batch.size(), finalStatus.values().stream().filter(v -> !v.equals("READY")).toList(),
                allReady ? "PASS" : "FAIL");
        return ok && viewOk && allReady;
    }

    static Map<String, String> uploadAll(HttpClient http, List<Job> jobs, byte[] video) throws Exception {
        Map<String, String> keys = new ConcurrentHashMap<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (Job j : jobs) {
                pool.submit(() -> {
                    try {
                        String t = http.send(creator(j, HttpRequest.newBuilder(URI.create(BASE + "/api/core/creator/jobs/"
                                        + j.id() + "/drafts/upload"))).header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"contentType\":\"video/mp4\",\"sizeBytes\":" + video.length + "}")).build(),
                                HttpResponse.BodyHandlers.ofString()).body();
                        int put = http.send(HttpRequest.newBuilder(URI.create(field(t, "url"))).header("Content-Type",
                                "video/mp4").PUT(HttpRequest.BodyPublishers.ofByteArray(video)).build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode();
                        if (put == 200) {
                            keys.put(j.id(), field(t, "key"));
                        } else {
                            System.out.println("upload failed " + put);
                        }
                    } catch (Exception e) {
                        System.out.println("upload failed " + e);
                    }
                });
            }
        }
        return keys;
    }

    static String approveAll(String draftId) {
        StringBuilder a = new StringBuilder();
        for (String k : List.of("hook", "product", "key_points", "cta", "disclosure")) {
            a.append(a.isEmpty() ? "" : ",").append("{\"key\":\"").append(k).append("\",\"ok\":true}");
        }
        return "{\"draftId\":\"" + draftId + "\",\"decision\":\"APPROVE\",\"answers\":[" + a + "]}";
    }

    interface Req {
        HttpRequest build(Job j);
    }

    static boolean phase(String name, int rps, List<Job> jobs, HttpClient http, Req req, int expect) throws Exception {
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        measure(rps, jobs, http, req, lat, statuses);
        List<Long> s = new ArrayList<>(lat);
        Collections.sort(s);
        long failed = statuses.entrySet().stream().filter(e -> !e.getKey().equals(String.valueOf(expect)))
                .mapToLong(e -> e.getValue().sum()).sum();
        long limit = name.startsWith("SUBMIT") || name.startsWith("REVIEW") ? WRITE_P95_LIMIT_MS : P95_LIMIT_MS;
        boolean pass = pct(s, 95) <= limit && failed == 0;
        System.out.printf("%-17s n=%5d at %d rps  p50=%3dms p95=%3dms p99=%3dms max=%4dms  %s  GATE %s%n", name, s.size(),
                rps, pct(s, 50), pct(s, 95), pct(s, 99), s.getLast(), statuses, pass ? "PASS" : "FAIL");
        return pass;
    }

    static void measure(int rps, List<Job> jobs, HttpClient http, Req req, List<Long> lat,
                        Map<String, LongAdder> statuses) throws Exception {
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
    }

    static HttpRequest.Builder brand(Job j, HttpRequest.Builder b) {
        return b.header("X-Dev-Role", "BRAND_ADMIN").header("X-Dev-User-Id", UUID.randomUUID().toString())
                .header("X-Dev-Org-Id", j.brand()).timeout(Duration.ofSeconds(10));
    }

    static HttpRequest.Builder creator(Job j, HttpRequest.Builder b) {
        return b.header("X-Dev-Role", "CREATOR").header("X-Dev-User-Id", j.creator()).timeout(Duration.ofSeconds(30));
    }

    static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\":\"") + name.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
