package com.loofie.core.job.app;

import java.util.Objects;
import java.util.UUID;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;

/**
 * Input to the single transition entry point.
 *
 * @param creatorId       required for INVITE and APPROVE_SWAP (the creator being put on the job); otherwise null
 * @param correlationId   ties together everything caused by one request; generated when null
 * @param details         action-specific input (shipment, photo, issue type, reason code); never null
 * @param expectedVersion the job version the caller's screen showed; when set and the job has moved
 *                        on since, the call is refused instead of acting on stale information
 */
public record TransitionCommand(UUID jobId, JobAction action, Actor actor, String reason, UUID creatorId,
                                UUID correlationId, TransitionDetails details, Long expectedVersion) {

    public TransitionCommand {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        details = details == null ? TransitionDetails.NONE : details;
    }

    public static TransitionCommand of(UUID jobId, JobAction action, Actor actor, String reason) {
        return new TransitionCommand(jobId, action, actor, reason, null, null, TransitionDetails.NONE, null);
    }

    public TransitionCommand withCreator(UUID creatorId) {
        return new TransitionCommand(jobId, action, actor, reason, creatorId, correlationId, details, expectedVersion);
    }

    public TransitionCommand with(TransitionDetails details) {
        return new TransitionCommand(jobId, action, actor, reason, creatorId, correlationId, details, expectedVersion);
    }

    public TransitionCommand expectingVersion(Long expectedVersion) {
        return new TransitionCommand(jobId, action, actor, reason, creatorId, correlationId, details, expectedVersion);
    }
}
