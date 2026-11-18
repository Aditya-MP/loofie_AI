package com.loofie.core.messaging;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox. {@link #enqueue} must run inside the caller's business transaction, so
 * the event is stored if and only if the state change commits. {@link OutboxRelay} sends the rows on
 * (today: to Backend A over HTTP).
 */
@Repository
public class Outbox {

    /** The stream every job event goes to (kept from the Kafka topic name; the column is {@code topic}). */
    public static final String JOB_STREAM = "core.job.v1";

    /** Creator data for Backend A (profile pushes, Instagram data deletion); keyed by creator id. */
    public static final String CREATOR_STREAM = "core.creator.v1";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Outbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String messageKey, EventEnvelope envelope) {
        jdbc.sql("""
                INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at)
                VALUES (:id, :topic, :key, :eventType, CAST(:envelope AS jsonb), :createdAt)
                """)
                .param("id", envelope.eventId())
                .param("topic", topic)
                .param("key", messageKey)
                .param("eventType", envelope.eventType())
                .param("envelope", json.writeValueAsString(envelope.toMap()))
                .param("createdAt", OffsetDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC))
                .update();
    }
}
