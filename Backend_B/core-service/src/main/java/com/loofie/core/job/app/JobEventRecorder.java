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
import com.loofie.core.messaging.Topics;

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
    UUID recordStateChange(Job job, UUID assignmentId, JobStatus from, JobAction action, Actor actor, String reason,
                           ReasonCode reasonCode, DeliveryIssueType issueType, Instant occurredAt, UUID correlationId,
                           UUID causationId) {
        UUID eventId = Ids.newId();

        // No PII here: this payload feeds the F12 log (LLM training). Ids and states only.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", job.getOrderId().toString());
        payload.put("jobId", job.getId().toString());
        payload.put("assignmentId", assignmentId == null ? null : assignmentId.toString());
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

        eventLog.append(new JobEventRecord(eventId, JOB_STATE_CHANGED, job.getOrderId(), job.getId(), job.getVersion(),
                assignmentId,
                actor.role().name(), actor.userId(), action.name(), from.name(), job.getStatus().name(), reason,
                reasonCode == null ? null : reasonCode.name(),
                json.writeValueAsString(payload), occurredAt));

        EventEnvelope envelope = new EventEnvelope(eventId, JOB_STATE_CHANGED, 1, occurredAt, EventEnvelope.PRODUCER,
                job.getId(), job.getVersion(), correlationId, causationId, payload);
        outbox.enqueue(Topics.CORE_JOB, job.getId().toString(), envelope);
        return eventId;
    }
}
