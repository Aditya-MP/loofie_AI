package com.loofie.core.job.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.common.web.Idempotency;
import com.loofie.core.common.web.RequestIdFilter;
import com.loofie.core.job.app.JobActions;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.app.TransitionResult;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.DeliverySource;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.ReasonCode;

import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP entry to the state machine for every role. The engine decides who may do what; this
 * controller only maps the request. Ops console actions (F10) use this same endpoint.
 *
 * <p>Action-specific fields: {@code courier} + {@code trackingNumber} (MARK_SHIPPED, RESHIP),
 * {@code photoRef} + optional {@code shipmentAttempt} (CONFIRM_RECEIVED), {@code issueType}
 * (REPORT_DELIVERY_ISSUE), {@code deliverySource} + optional {@code deliveredAt} (COURIER_DELIVERED),
 * {@code reasonCode} (ops only).
 *
 * <p><b>Retries.</b> Clients should send an {@code Idempotency-Key} header (a new UUID per user
 * action, reused for retries of that action). A retry then returns the first response with
 * {@code Idempotent-Replayed: true} instead of acting twice. {@code expectedVersion} (the job
 * version the screen showed) refuses the action with 409 if the job changed in the meantime.
 */
@RestController
@RequestMapping("/api/core/jobs/{jobId}/transitions")
class JobTransitionController {

    record TransitionRequest(@NotNull JobAction action, @Size(max = 1000) String reason, UUID creatorId,
                             @Size(max = 64) String courier, @Size(max = 64) String trackingNumber,
                             @Size(max = 512) String photoRef, DeliveryIssueType issueType, ReasonCode reasonCode,
                             @PositiveOrZero Long expectedVersion, Instant deliveredAt, DeliverySource deliverySource,
                             @Positive Integer shipmentAttempt, @Size(max = 32) String courierCode,
                             @Size(max = 300) String trackingLink, @Size(max = 512) String handoverPhotoRef,
                             @Size(max = 512) String evidenceRef) {
    }

    private final JobActions engine;
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final boolean requireKeyForAll;

    /**
     * @param requireKeyForAll {@code loofie.api.require-idempotency-key}: when true every transition needs an
     *                         {@code Idempotency-Key}. Off until the apps send one; then switch it on in prod.
     */
    JobTransitionController(JobActions engine, Idempotency idempotency, JsonMapper json,
                            @Value("${loofie.api.require-idempotency-key:false}") boolean requireKeyForAll) {
        this.engine = engine;
        this.idempotency = idempotency;
        this.json = json;
        this.requireKeyForAll = requireKeyForAll;
    }

    @PostMapping
    ResponseEntity<TransitionResult> transition(@PathVariable UUID jobId, @Valid @RequestBody TransitionRequest body,
                                                Actor actor,
                                                @RequestHeader(name = Idempotency.HEADER, required = false) String key) {
        TransitionDetails details = new TransitionDetails(body.courier(), body.trackingNumber(), body.photoRef(),
                body.issueType(), body.reasonCode(), body.deliveredAt(), body.deliverySource(), body.shipmentAttempt(),
                body.courierCode(), body.trackingLink(), body.handoverPhotoRef(), body.evidenceRef());
        TransitionCommand cmd = new TransitionCommand(jobId, body.action(), actor, body.reason(), body.creatorId(),
                RequestIdFilter.current(), details, body.expectedVersion());
        if (key == null) {
            // OPS_EXTEND is the one action a plain retry would apply twice (+96h instead of +48h).
            if (requireKeyForAll || body.action() == JobAction.OPS_EXTEND) {
                throw new Idempotency.KeyRequiredException(body.action().name());
            }
            return ResponseEntity.ok(engine.run(cmd));
        }
        String scope = actor.role().name() + ":" + actor.userId();
        String hash = Idempotency.hash("POST /api/core/jobs/{jobId}/transitions", jobId.toString(),
                json.writeValueAsString(body));
        Idempotency.Outcome<TransitionResult> outcome = idempotency.run(scope, key, hash, TransitionResult.class,
                () -> engine.run(cmd));
        return ResponseEntity.ok()
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.value());
    }
}
