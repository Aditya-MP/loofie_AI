package com.loofie.core.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns one outbox envelope into a Backend A {@code POST /ai/feedback} request (their docs/API.md).
 * Done at send time, so the outbox stays generic and a contract change also applies to rows still waiting.
 *
 * <p><b>Only what the models need is sent:</b> ids, states, codes. Never the ops free-text reason (not in the
 * envelope anyway), the acting user's id, the order or the assignment. Keys whose value is null are left out:
 * Backend A checks a key's value whenever the key is present.
 *
 * <p>Events without a creator (for example payment confirmed) are not sent: Backend A requires a creator, and
 * these teach the models nothing.
 */
@Component
public class AiFeedbackMapper {

    static final String STATE_CHANGED = "JobStateChanged";
    static final String DEADLINE_NOTICE = "JobDeadlineNotice";

    private final JsonMapper json;

    public AiFeedbackMapper(JsonMapper json) {
        this.json = json;
    }

    /** The request body, or empty if this event is not for Backend A. */
    public Optional<Map<String, Object>> toRequest(String eventType, String envelopeJson) {
        JsonNode envelope = json.readTree(envelopeJson);
        JsonNode p = envelope.get("payload");
        if (p == null || text(p, "creatorId") == null || text(p, "jobId") == null) {
            return Optional.empty();
        }
        return switch (eventType) {
            case STATE_CHANGED -> Optional.of(request(envelope, "job_state_changed", actorType(text(p, "actorType")),
                    stateChanged(p)));
            case DEADLINE_NOTICE -> Optional.of(request(envelope, "job_timer_fired", "system", timerFired(p)));
            default -> Optional.empty();
        };
    }

    private static Map<String, Object> stateChanged(JsonNode p) {
        Map<String, Object> out = common(p);
        out.put("from", text(p, "fromState"));
        out.put("to", text(p, "toState"));
        putIfPresent(out, "reasonCode", p.get("reasonCode"));
        putIfPresent(out, "faultParty", p.get("faultParty"));
        putIfPresent(out, "issue_type", p.get("deliveryIssueType"));
        putIfPresent(out, "action", p.get("action"));
        putIfPresent(out, "shipmentAttempt", p.get("shipmentAttempt"));
        putIfPresent(out, "deliverySource", p.get("deliverySource"));
        putIfPresent(out, "deliveredAt", p.get("deliveredAt"));
        putIfPresent(out, "olderShipmentReceived", p.get("olderShipmentReceived"));
        putIfPresent(out, "afterReceipt", p.get("afterReceipt")); // damage found after "Received"
        return out;
    }

    private static Map<String, Object> timerFired(JsonNode p) {
        Map<String, Object> out = common(p);
        out.put("kind", text(p, "kind"));
        putIfPresent(out, "audience", p.get("audience"));
        putIfPresent(out, "due_at", p.get("dueAt"));
        return out;
    }

    private static Map<String, Object> common(JsonNode p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("creator_ref", text(p, "creatorId"));
        out.put("job_id", text(p, "jobId"));
        return out;
    }

    private static Map<String, Object> request(JsonNode envelope, String type, String actorType,
                                               Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_type", type);
        body.put("actor_type", actorType);
        body.put("subject_type", "job");
        body.put("subject_id", payload.get("job_id"));
        body.put("event_id", text(envelope, "eventId"));
        body.put("occurred_at", text(envelope, "occurredAt"));
        body.put("payload", payload);
        return body;
    }

    /** Our roles to Backend A's actor types. */
    static String actorType(String role) {
        if (role == null) {
            return "system";
        }
        return switch (role) {
            case "BRAND_ADMIN", "BRAND_MEMBER" -> "brand";
            case "CREATOR" -> "creator";
            case "OPS", "OPS_LEAD", "FOUNDER" -> "ops";
            default -> "system";
        };
    }

    private static void putIfPresent(Map<String, Object> out, String key, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isBoolean()) {
            out.put(key, value.asBoolean());
        } else if (value.isIntegralNumber()) {
            out.put(key, value.asLong());
        } else if (value.isArray()) {
            out.put(key, value.valueStream().map(JsonNode::asString).toList());
        } else {
            out.put(key, value.asString());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
