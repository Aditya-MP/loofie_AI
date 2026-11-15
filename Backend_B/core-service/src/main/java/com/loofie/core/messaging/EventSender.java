package com.loofie.core.messaging;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Where {@link OutboxRelay} sends rows. Today one implementation: {@link AiFeedbackSender} (HTTP to Backend A).
 * A send completes with a {@link Delivery}, or fails with {@link Rejected} (never retry: park the row),
 * {@link Unreachable} (stop the run, retry later) or any other exception (retry with backoff).
 */
public interface EventSender {

    /** False when the destination is not configured in this environment: rows wait until it is. */
    boolean ready();

    CompletableFuture<Delivery> send(Message message);

    /** One outbox row, as stored. */
    record Message(UUID id, String stream, String key, String eventType, String envelope) {
    }

    enum Delivery {
        /** Accepted (also when the receiver says it already had it). */
        DELIVERED,
        /** The receiver already has this event id with different content: it has the event, retrying cannot help. */
        CONFLICT,
        /** Nothing to send for this row (the receiver does not take it). Done, never sent. */
        SKIPPED
    }

    /** The receiver will never accept this row as it is (bad request, unknown type). */
    final class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    /** The receiver cannot be reached at all (connection refused, unknown host). */
    final class Unreachable extends RuntimeException {
        public Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
