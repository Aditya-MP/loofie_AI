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
import com.loofie.core.job.app.TransitionResult;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;

/**
 * HTTP entry to the state machine for every role. The engine decides who may do what; this
 * controller only maps the request. Ops console actions (F10) use this same endpoint.
 */
@RestController
@RequestMapping("/api/core/jobs/{jobId}/transitions")
class JobTransitionController {

    record TransitionRequest(@NotNull JobAction action, @Size(max = 1000) String reason, UUID creatorId) {
    }

    private final JobTransitionService engine;

    JobTransitionController(JobTransitionService engine) {
        this.engine = engine;
    }

    @PostMapping
    TransitionResult transition(@PathVariable UUID jobId, @Valid @RequestBody TransitionRequest body, Actor actor,
                                @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
        return engine.transition(new TransitionCommand(jobId, body.action(), actor, body.reason(), body.creatorId(),
                correlationId));
    }
}
