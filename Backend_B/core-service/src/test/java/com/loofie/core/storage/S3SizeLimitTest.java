package com.loofie.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The claim the upload size limit rests on (open ask 45), against a real S3-compatible store: a URL signed by our code
 * for N bytes is accepted with exactly N bytes and refused (403, nothing stored) with any other size.
 *
 * <p>Opt-in. Run against MinIO, for example:
 * <pre>
 * docker run -d --name s3-check -p 9000:9000 -e MINIO_ROOT_USER=check -e MINIO_ROOT_PASSWORD=check-secret-1 \
 *     minio/minio server /data
 * (create the bucket "loofie-check", e.g. with the minio/mc image)
 * S3_CHECK_ENDPOINT=http://localhost:9000 S3_CHECK_KEY=check S3_CHECK_SECRET=check-secret-1 \
 *     ./mvnw test -Dtest=S3SizeLimitTest
 * </pre>
 * Against AWS S3 itself: the same variables with an https endpoint, a test bucket and {@code S3_CHECK_REGION}.
 */
@EnabledIfEnvironmentVariable(named = "S3_CHECK_ENDPOINT", matches = ".+")
class S3SizeLimitTest {

    private final StorageProperties props = new StorageProperties("s3", System.getenv("S3_CHECK_ENDPOINT"),
            System.getenv().getOrDefault("S3_CHECK_REGION", "us-east-1"),
            System.getenv().getOrDefault("S3_CHECK_BUCKET", "loofie-check"), true, System.getenv("S3_CHECK_KEY"),
            System.getenv("S3_CHECK_SECRET"), null, null, true);
    private final S3ObjectStore store = new S3ObjectStore(props, Clock.systemUTC());
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void theStoreAcceptsOnlyTheSignedSize() throws Exception {
        String key = "drafts/check/" + UUID.randomUUID() + ".mp4";
        ObjectStore.Ticket t = store.presignPut(key, "video/mp4", 1000, Duration.ofMinutes(5));

        assertThat(put(t, 1001)).as("one byte more").isEqualTo(403);
        assertThat(put(t, 999)).as("one byte less").isEqualTo(403);
        assertThat(put(t, 50_000)).as("much bigger").isEqualTo(403);
        assertThat(store.stat(key)).as("nothing stored after refused bodies").isEmpty();

        assertThat(put(t, 1000)).as("exactly the signed size").isEqualTo(200);
        assertThat(store.stat(key)).hasValueSatisfying(info -> assertThat(info.size()).isEqualTo(1000));
        store.delete(key);
    }

    @Test
    void theSignedTypeStillHolds() throws Exception {
        String key = "creator-evidence/check/" + UUID.randomUUID() + ".png";
        ObjectStore.Ticket t = store.presignPut(key, "image/png", 10, Duration.ofMinutes(5));
        int status = http.send(HttpRequest.newBuilder(t.url()).header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[10])).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        assertThat(status).isEqualTo(403);
    }

    /** Java's client sets Content-Length from the body, as a browser does from the file. */
    private int put(ObjectStore.Ticket t, int bytes) throws Exception {
        return http.send(HttpRequest.newBuilder(t.url()).header("Content-Type", t.headers().get("Content-Type"))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[bytes])).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
