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

    /** A URL the browser can PUT one file to, with exactly these headers, until {@code expiresAt}. */
    Ticket presignPut(String key, String contentType, Duration ttl);

    /** A URL to view one file until it expires (ops review). */
    URI presignGet(String key, Duration ttl);

    /** Size and type of a stored object, or empty if nothing was uploaded under this key. */
    Optional<ObjectInfo> stat(String key);

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
