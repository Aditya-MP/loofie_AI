package com.loofie.core.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The standard envelope for every event Backend B publishes (contracts/events/envelope.v1.json).
 */
public record EventEnvelope(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, String producer,
                            UUID aggregateId, long aggregateVersion, UUID correlationId, UUID causationId,
                            Map<String, Object> payload) {

    public static final String PRODUCER = "core-service";

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId.toString());
        m.put("eventType", eventType);
        m.put("schemaVersion", schemaVersion);
        m.put("occurredAt", occurredAt.toString());
        m.put("producer", producer);
        m.put("aggregateId", aggregateId.toString());
        m.put("aggregateVersion", aggregateVersion);
        m.put("correlationId", correlationId.toString());
        m.put("causationId", causationId == null ? null : causationId.toString());
        m.put("payload", payload);
        return m;
    }
}
