package com.loofie.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** The envelope-to-/ai/feedback mapping, against Backend A's docs/API.md and app/feedback/events.py rules. */
class AiFeedbackMapperTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final AiFeedbackMapper mapper = new AiFeedbackMapper(json);

    @Test
    @SuppressWarnings("unchecked")
    void aDisputeCarriesItsReasonCodeAndFaultPartyAndTheIssueType() {
        Map<String, Object> p = statePayload();
        p.put("reasonCode", "DAMAGED_IN_TRANSIT");
        p.put("faultParty", "COURIER");
        p.put("deliveryIssueType", "DAMAGED");

        Map<String, Object> body = mapper.toRequest(AiFeedbackMapper.STATE_CHANGED, envelope(p)).orElseThrow();
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");

        assertThat(payload).containsEntry("reasonCode", "DAMAGED_IN_TRANSIT").containsEntry("faultParty", "COURIER")
                .containsEntry("issue_type", "DAMAGED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void keysWithoutAValueAreLeftOutBecauseBackendAChecksEveryKeyThatIsPresent() {
        Map<String, Object> p = statePayload();
        p.put("reasonCode", null);
        p.put("faultParty", null);
        p.put("deliveryIssueType", null);
        p.put("hasReason", true);
        p.put("orderId", UUID.randomUUID().toString());
        p.put("assignmentId", UUID.randomUUID().toString());
        p.put("actorId", UUID.randomUUID().toString());

        Map<String, Object> payload = (Map<String, Object>) mapper.toRequest(AiFeedbackMapper.STATE_CHANGED,
                envelope(p)).orElseThrow().get("payload");

        assertThat(payload).containsOnlyKeys("creator_ref", "job_id", "from", "to", "action");
    }

    @Test
    void ourRolesBecomeBackendAActorTypes() {
        assertThat(AiFeedbackMapper.actorType("BRAND_ADMIN")).isEqualTo("brand");
        assertThat(AiFeedbackMapper.actorType("BRAND_MEMBER")).isEqualTo("brand");
        assertThat(AiFeedbackMapper.actorType("CREATOR")).isEqualTo("creator");
        assertThat(AiFeedbackMapper.actorType("OPS")).isEqualTo("ops");
        assertThat(AiFeedbackMapper.actorType("SYSTEM")).isEqualTo("system");
        assertThat(AiFeedbackMapper.actorType(null)).isEqualTo("system");
    }

    @Test
    void noCreatorOrAnEventTypeBackendADoesNotTakeMeansNothingIsSent() {
        Map<String, Object> p = statePayload();
        p.put("creatorId", null);
        assertThat(mapper.toRequest(AiFeedbackMapper.STATE_CHANGED, envelope(p))).isEmpty();
        assertThat(mapper.toRequest("SomethingElse", envelope(statePayload()))).isEmpty();
    }

    @Test
    void theRequestUsesOurEventIdAndTime() {
        UUID eventId = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-15T10:00:00Z");
        Optional<Map<String, Object>> body = mapper.toRequest(AiFeedbackMapper.STATE_CHANGED,
                envelope(eventId, at, statePayload()));

        assertThat(body.orElseThrow()).containsEntry("event_id", eventId.toString())
                .containsEntry("occurred_at", "2026-11-15T10:00:00Z").containsEntry("event_type", "job_state_changed")
                .containsEntry("actor_type", "creator").containsEntry("subject_type", "job");
    }

    @Test
    void httpAnswersAreClassifiedAsAgreed() {
        assertThat(AiFeedbackSender.outcome(200, "{}")).isEqualTo(EventSender.Delivery.DELIVERED);
        assertThat(AiFeedbackSender.outcome(409, "{}")).isEqualTo(EventSender.Delivery.CONFLICT);
        for (int rejected : List.of(400, 404, 422)) {
            assertThat(thrown(rejected)).isInstanceOf(EventSender.Rejected.class);
        }
        for (int retried : List.of(401, 403, 429, 500, 502, 503)) {
            assertThat(thrown(retried)).isNotInstanceOf(EventSender.Rejected.class).isNotNull();
        }
    }

    private static RuntimeException thrown(int status) {
        try {
            AiFeedbackSender.outcome(status, "{}");
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static Map<String, Object> statePayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("jobId", UUID.randomUUID().toString());
        p.put("creatorId", UUID.randomUUID().toString());
        p.put("action", "SUBMIT_DRAFT");
        p.put("fromState", "IN_PRODUCTION");
        p.put("toState", "IN_REVIEW");
        p.put("actorType", "CREATOR");
        return p;
    }

    private String envelope(Map<String, Object> payload) {
        return envelope(UUID.randomUUID(), Instant.now(), payload);
    }

    private String envelope(UUID eventId, Instant at, Map<String, Object> payload) {
        return json.writeValueAsString(new EventEnvelope(eventId, AiFeedbackMapper.STATE_CHANGED, 1, at,
                EventEnvelope.PRODUCER, UUID.randomUUID(), 1, UUID.randomUUID(), null, payload).toMap());
    }
}
