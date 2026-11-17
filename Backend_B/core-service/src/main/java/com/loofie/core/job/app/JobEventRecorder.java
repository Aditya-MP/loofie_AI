package com.loofie.core.job.app;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.events.JobEventLog;
import com.loofie.core.events.JobEventLog.JobEventRecord;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.ReasonCode;
import com.loofie.core.messaging.EventEnvelope;
import com.loofie.core.messaging.Outbox;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one state change to job_events and the outbox, in the caller's transaction.
 * The same eventId is used in both, so downstream consumers and the F12 log can de-duplicate.
 */
@Component
class JobEventRecorder {

    static final String JOB_STATE_CHANGED = "JobStateChanged";

    private final JobEventLog eventLog;
    private final Outbox outbox;
    private final JsonMapper json;

    JobEventRecorder(JobEventLog eventLog, Outbox outbox, JsonMapper json) {
        this.eventLog = eventLog;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    UUID recordStateChange(Job job, UUID assignmentId, UUID creatorId, JobStatus from, JobAction action, Actor actor, String reason,
                           ReasonCode reasonCode, DeliveryIssueType issueType, Map<String, Object> facts,
                           Instant occurredAt, UUID correlationId,
                           UUID causationId) {
        UUID eventId = Ids.newId();

        // No PII here: this payload feeds the F12 log (LLM training). Ids and states only.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", job.getOrderId().toString());
        payload.put("jobId", job.getId().toString());
        payload.put("assignmentId", assignmentId == null ? null : assignmentId.toString());
        // Our creator id (an internal id, not personal data). Backend A's /ai/feedback needs it as creator_ref.
        payload.put("creatorId", creatorId == null ? null : creatorId.toString());
        payload.put("action", action.name());
        payload.put("fromState", from.name());
        payload.put("toState", job.getStatus().name());
        payload.put("actorType", actor.role().name());
        payload.put("actorId", actor.userId() == null ? null : actor.userId().toString());
        // The free-text reason (ops notes) is deliberately NOT in the payload: it can hold personal data and
        // "ops notes stay in the backend log only" (docs/product-decisions.md). It is kept in job_events.reason.
        payload.put("hasReason", reason != null && !reason.isBlank());
        // Fixed codes, safe for other services and the AI feedback log.
        payload.put("reasonCode", reasonCode == null ? null : reasonCode.name());
        payload.put("faultParty", reasonCode == null ? null : reasonCode.fault().name());
        payload.put("deliveryIssueType", issueType == null ? null : issueType.name());
        // Action-specific facts (shipment attempt, delivery source...): ids and codes only, never free text.
        if (facts != null) {
            facts.forEach(payload::putIfAbsent);
        }

        eventLog.append(new JobEventRecord(eventId, JOB_STATE_CHANGED, job.getOrderId(), job.getId(), job.getVersion(),
                assignmentId,
                actor.role().name(), actor.userId(), action.name(), from.name(), job.getStatus().name(), reason,
                reasonCode == null ? null : reasonCode.name(),
                json.writeValueAsString(payload), occurredAt));

        EventEnvelope envelope = envelopeFor(eventId, job, occurredAt, correlationId, causationId, payload);
        outbox.enqueue(Outbox.JOB_STREAM, job.getId().toString(), envelope);
        return eventId;
    }

    static final String JOB_OUTCOME = "JobOutcome";

    /**
     * How one creator's part of a job went, for the AI's track record (DECISIONS.md section 9). Derived from the
     * job's own log, so it is not stored again in job_events; it goes to the outbox in the caller's transaction.
     *
     * @param code null on a normal close; the cause's reason code when the creator's part ended early
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void recordOutcome(Job job, UUID assignmentId, UUID creatorId, boolean onTime, int revisions, boolean disputed,
                       boolean replaced, ReasonCode code, Instant occurredAt, UUID correlationId, UUID causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.getId().toString());
        payload.put("assignmentId", assignmentId.toString());
        payload.put("creatorId", creatorId.toString());
        payload.put("onTime", onTime);
        payload.put("revisions", revisions);
        payload.put("disputed", disputed);
        payload.put("replaced", replaced);
        payload.put("reasonCode", code == null ? null : code.name());
        payload.put("faultParty", code == null ? null : code.fault().name());
        UUID eventId = Ids.newId();
        outbox.enqueue(Outbox.JOB_STREAM, job.getId().toString(), new EventEnvelope(eventId, JOB_OUTCOME, 1, occurredAt,
                EventEnvelope.PRODUCER, job.getId(), job.getVersion(), correlationId, causationId, payload));
    }

    private static EventEnvelope envelopeFor(UUID eventId, Job job, Instant occurredAt, UUID correlationId,
                                             UUID causationId, Map<String, Object> payload) {
        return new EventEnvelope(eventId, JOB_STATE_CHANGED, 1, occurredAt, EventEnvelope.PRODUCER,
                job.getId(), job.getVersion(), correlationId, causationId, payload);
    }
}
