package com.loofie.core.events;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Appends rows to job_events (F12 format). Insert-only: the table rejects UPDATE, DELETE and
 * TRUNCATE with a trigger, so there are deliberately no other methods here.
 */
@Repository
public class JobEventLog {

    private final JdbcClient jdbc;

    public JobEventLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(JobEventRecord e) {
        jdbc.sql("""
                INSERT INTO job_events (event_id, event_type, order_id, job_id, job_version, assignment_id, actor_type,
                                        actor_id, action, from_state, to_state, reason, reason_code, payload, occurred_at)
                VALUES (:eventId, :eventType, :orderId, :jobId, :jobVersion, :assignmentId, :actorType,
                        :actorId, :action, :fromState, :toState, :reason, :reasonCode, CAST(:payload AS jsonb), :occurredAt)
                """)
                .param("eventId", e.eventId())
                .param("eventType", e.eventType())
                .param("orderId", e.orderId())
                .param("jobId", e.jobId())
                .param("jobVersion", e.jobVersion())
                .param("assignmentId", e.assignmentId())
                .param("actorType", e.actorType())
                .param("actorId", e.actorId())
                .param("action", e.action())
                .param("fromState", e.fromState())
                .param("toState", e.toState())
                .param("reason", e.reason())
                .param("reasonCode", e.reasonCode())
                .param("payload", e.payloadJson())
                .param("occurredAt", utc(e.occurredAt()))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record JobEventRecord(UUID eventId, String eventType, UUID orderId, UUID jobId, Long jobVersion,
                                 UUID assignmentId,
                                 String actorType, UUID actorId, String action, String fromState, String toState,
                                 String reason, String reasonCode, String payloadJson, Instant occurredAt) {
    }
}
