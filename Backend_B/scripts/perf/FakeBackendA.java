import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for Backend A's Instagram and profile endpoints, for load runs only (round C). Every connection is
 * "active"; every handle has verified numbers. Answers in well under a millisecond, so a load run measures Backend B.
 *
 * Usage: java FakeBackendA.java [port=8100] [apiKey=perf-key]
 */
public class FakeBackendA {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8100;
        String key = args.length > 1 ? args[1] : "perf-key";
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 1000);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            try (ex) {
                ex.getRequestBody().readAllBytes();
                if (!key.equals(ex.getRequestHeaders().getFirst("X-API-Key"))) {
                    reply(ex, 401, "{\"detail\":\"bad key\"}");
                    return;
                }
                String path = ex.getRequestURI().getPath();
                if (path.startsWith("/ai/instagram/accounts/")) {
                    String ref = path.substring("/ai/instagram/accounts/".length());
                    reply(ex, 200, "{\"account_ref\":\"" + ref + "\",\"handle\":\"ig." + ref.substring(0, Math.min(8, ref.length()))
                            + "\",\"status\":\"active\",\"insights_allowed\":true,\"token_expires_at\":"
                            + "\"2027-01-13T10:00:00Z\",\"last_synced_at\":\"2026-11-18T10:00:00Z\",\"last_error\":null}");
                } else if (path.equals("/ai/instagram/login-url")) {
                    reply(ex, 200, "{\"url\":\"https://www.instagram.com/oauth/authorize?state=x\",\"expires_in\":600}");
                } else if (path.startsWith("/ai/creators/")) {
                    reply(ex, 200, "{\"handle\":\"x\",\"stats\":{\"followers\":12000,\"median_views\":4000},"
                            + "\"metric_sources\":{\"followers\":{\"source\":\"API_VERIFIED\",\"captured_on\":"
                            + "\"2026-11-18\"}}}");
                } else {
                    reply(ex, 200, "{}");
                }
            }
        });
        server.start();
        System.out.println("Fake Backend A on http://localhost:" + port);
    }

    private static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }
}
