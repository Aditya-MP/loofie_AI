package com.loofie.core.messaging;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer support. Call {@link #firstTime} at the start of the handler's transaction:
 * if it returns false the event was already handled and the handler must do nothing. Because the
 * inbox row commits with the handler's changes, a crash mid-handler leaves no row and the
 * redelivered event is processed again.
 */
@Repository
public class Inbox {

    private final JdbcClient jdbc;
    private final Clock clock;

    public Inbox(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumer, UUID eventId) {
        int inserted = jdbc.sql("""
                INSERT INTO processed_events (consumer, event_id, processed_at)
                VALUES (:consumer, :eventId, :now)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .param("now", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }
}
