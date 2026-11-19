package com.loofie.core.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Where uploaded files live (decision C1: S3-compatible; AWS S3 Mumbai or Cloudflare R2). The app never streams file
 * bytes itself: the browser uploads and downloads with short-lived signed URLs.
 */
public interface ObjectStore {

    /** False when no store is configured here: upload requests answer 503 and nothing else breaks. */
    boolean ready();

    /**
     * A URL the browser can PUT one file of exactly {@code sizeBytes} to, with exactly these headers, until
     * {@code expiresAt}. The store refuses a body of any other size, so a link can never hold more than was allowed.
     */
    Ticket presignPut(String key, String contentType, long sizeBytes, Duration ttl);

    /** A URL to view one file until it expires (ops review). */
    URI presignGet(String key, Duration ttl);

    /** Size and type of a stored object, or empty if nothing was uploaded under this key. */
    Optional<ObjectInfo> stat(String key);

    /**
     * Server side only (the F7 media worker): copies one stored file to a local path. Never on a request thread.
     *
     * @throws StoreUnavailableException when the store cannot be reached or the file is missing
     */
    void download(String key, java.nio.file.Path target);

    /** Server side only (the F7 media worker): stores a local file under {@code key}. */
    void upload(String key, java.nio.file.Path source, String contentType);

    /** Removes the object; removing a missing object is not an error. */
    void delete(String key);

    record Ticket(String method, URI url, Map<String, String> headers, Instant expiresAt) {
    }

    record ObjectInfo(long size, String contentType) {
    }

    /** The store could not be reached or refused us: the caller answers 503. */
    final class StoreUnavailableException extends com.loofie.core.common.DependencyUnavailableException {
        public StoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
