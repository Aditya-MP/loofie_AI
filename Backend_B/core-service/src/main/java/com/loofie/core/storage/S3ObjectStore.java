package com.loofie.core.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * S3-compatible store over plain HTTP with presigned URLs (no SDK). The app itself only checks (HEAD) and removes
 * (DELETE) objects; bytes go straight between the browser and the store.
 */
final class S3ObjectStore implements ObjectStore {

    private static final Duration SERVER_CALL_TTL = Duration.ofMinutes(1);

    private final StorageProperties props;
    private final Clock clock;
    private final HttpClient http;
    private final String base;

    S3ObjectStore(StorageProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.base = props.endpoint().trim().replaceAll("/+$", "");
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public Ticket presignPut(String key, String contentType, long sizeBytes, Duration ttl) {
        Instant now = clock.instant();
        URI url = SigV4.presign("PUT", objectUrl(key), props.region(), props.accessKey(), props.secretKey(), now, ttl,
                contentType, sizeBytes);
        return new Ticket("PUT", url, Map.of("Content-Type", contentType, "Content-Length", String.valueOf(sizeBytes)),
                now.plus(ttl));
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return sign("GET", key, clock.instant(), ttl, null);
    }

    @Override
    public Optional<ObjectInfo> stat(String key) {
        HttpResponse<Void> r = send(HttpRequest.newBuilder(sign("HEAD", key, clock.instant(), SERVER_CALL_TTL, null))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build());
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        if (r.statusCode() / 100 != 2) {
            throw new StoreUnavailableException("Storage answered " + r.statusCode() + " to HEAD", null);
        }
        long size = r.headers().firstValueAsLong("Content-Length").orElse(-1);
        return Optional.of(new ObjectInfo(size, r.headers().firstValue("Content-Type").orElse("")));
    }

    @Override
    public void delete(String key) {
        HttpResponse<Void> r = send(HttpRequest.newBuilder(sign("DELETE", key, clock.instant(), SERVER_CALL_TTL, null))
                .DELETE().timeout(Duration.ofSeconds(5)).build());
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) {
            throw new StoreUnavailableException("Storage answered " + r.statusCode() + " to DELETE", null);
        }
    }

    /** Long enough for a 500 MB video on a slow link; only the media worker downloads or uploads. */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);

    @Override
    public void download(String key, java.nio.file.Path target) {
        HttpRequest req = HttpRequest.newBuilder(sign("GET", key, clock.instant(), SERVER_CALL_TTL, null))
                .GET().timeout(TRANSFER_TIMEOUT).build();
        HttpResponse<java.nio.file.Path> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofFile(target));
        } catch (IOException e) {
            throw new StoreUnavailableException("Storage download failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException("Interrupted", e);
        }
        if (r.statusCode() / 100 != 2) {
            throw new StoreUnavailableException("Storage answered " + r.statusCode() + " to GET " + key, null);
        }
    }

    @Override
    public void upload(String key, java.nio.file.Path source, String contentType) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(sign("PUT", key, clock.instant(), SERVER_CALL_TTL, contentType))
                    .header("Content-Type", contentType).timeout(TRANSFER_TIMEOUT)
                    .PUT(HttpRequest.BodyPublishers.ofFile(source)).build();
        } catch (java.io.FileNotFoundException e) {
            throw new IllegalStateException("No local file to upload: " + source, e);
        }
        HttpResponse<Void> r = send(req);
        if (r.statusCode() / 100 != 2) {
            throw new StoreUnavailableException("Storage answered " + r.statusCode() + " to PUT " + key, null);
        }
    }

    URI objectUrl(String key) {
        String path = "/" + key;
        if (props.pathStyle()) {
            return URI.create(base + "/" + props.bucket() + path);
        }
        URI b = URI.create(base);
        return URI.create(b.getScheme() + "://" + props.bucket() + "." + b.getAuthority() + path);
    }

    private URI sign(String method, String key, Instant now, Duration ttl, String contentType) {
        return SigV4.presign(method, objectUrl(key), props.region(), props.accessKey(), props.secretKey(), now, ttl,
                contentType);
    }

    private HttpResponse<Void> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new StoreUnavailableException("Storage unreachable: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException("Interrupted", e);
        }
    }
}
