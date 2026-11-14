package com.loofie.core.messaging;

import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A received envelope (contracts/events/envelope.v1.json). Consumers read the payload as JSON. */
public record IncomingEvent(UUID eventId, String eventType, int schemaVersion, String producer, UUID aggregateId,
                            long aggregateVersion, UUID correlationId, JsonNode payload) {

    /** Parses an envelope; throws if a required field is missing, which sends the message to the DLT. */
    public static IncomingEvent parse(JsonMapper json, String value) {
        JsonNode n = json.readTree(value);
        return new IncomingEvent(
                UUID.fromString(required(n, "eventId").asString()),
                required(n, "eventType").asString(),
                required(n, "schemaVersion").asInt(),
                required(n, "producer").asString(),
                UUID.fromString(required(n, "aggregateId").asString()),
                required(n, "aggregateVersion").asLong(),
                UUID.fromString(required(n, "correlationId").asString()),
                required(n, "payload"));
    }

    private static JsonNode required(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("Envelope is missing " + field);
        }
        return v;
    }
}
