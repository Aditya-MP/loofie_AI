package com.loofie.core.job.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.app.TransitionResult;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.ReasonCode;

/**
 * HTTP entry to the state machine for every role. The engine decides who may do what; this
 * controller only maps the request. Ops console actions (F10) use this same endpoint.
 *
 * <p>Action-specific fields: {@code courier} + {@code trackingNumber} (MARK_SHIPPED, RESHIP),
 * {@code photoRef} (CONFIRM_RECEIVED), {@code issueType} (REPORT_DELIVERY_ISSUE),
 * {@code reasonCode} (ops only).
 */
@RestController
@RequestMapping("/api/core/jobs/{jobId}/transitions")
class JobTransitionController {

    record TransitionRequest(@NotNull JobAction action, @Size(max = 1000) String reason, UUID creatorId,
                             @Size(max = 64) String courier, @Size(max = 64) String trackingNumber,
                             @Size(max = 512) String photoRef, DeliveryIssueType issueType, ReasonCode reasonCode) {
    }

    private final JobTransitionService engine;

    JobTransitionController(JobTransitionService engine) {
        this.engine = engine;
    }

    @PostMapping
    TransitionResult transition(@PathVariable UUID jobId, @Valid @RequestBody TransitionRequest body, Actor actor,
                                @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
        TransitionDetails details = new TransitionDetails(body.courier(), body.trackingNumber(), body.photoRef(),
                body.issueType(), body.reasonCode());
        return engine.transition(new TransitionCommand(jobId, body.action(), actor, body.reason(), body.creatorId(),
                correlationId, details));
    }
}
