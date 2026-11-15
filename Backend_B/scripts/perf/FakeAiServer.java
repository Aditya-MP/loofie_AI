import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for Backend A's POST /ai/feedback when measuring the outbox relay: answers 200 after a fixed delay
 * (Backend A's own work per event) and prints requests per second and the most requests open at once.
 *
 * Usage: java FakeAiServer.java <port> <delayMs>
 */
public class FakeAiServer {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long delayMs = Long.parseLong(args[1]);
        LongAdder total = new LongAdder();
        AtomicInteger open = new AtomicInteger();
        AtomicInteger maxOpen = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 500);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/ai/feedback", ex -> {
            maxOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
            try {
                ex.getRequestBody().readAllBytes();
                Thread.sleep(delayMs);
                byte[] body = "{\"event_id\":\"x\",\"duplicate\":false}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
                total.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                open.decrementAndGet();
                ex.close();
            }
        });
        server.start();
        System.out.printf("fake /ai/feedback on %d, %d ms per request%n", port, delayMs);

        long last = 0;
        while (true) {
            Thread.sleep(1000);
            long now = total.sum();
            if (now != last) {
                System.out.printf("total=%d  last second=%d  max open=%d%n", now, now - last, maxOpen.get());
            }
            last = now;
        }
    }
}
