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
 * the event is stored if and only if the state change commits. A relay publishes rows to Kafka.
 */
@Repository
public class Outbox {

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
