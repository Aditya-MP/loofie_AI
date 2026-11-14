package com.loofie.core.job.domain;

import java.util.Set;

/**
 * One allowed move: {@code from + action -> to}, the roles that may trigger it, and an optional
 * follow-up action that the engine applies straight after as SYSTEM (for example
 * PRODUCT_RECEIVED immediately starting production).
 */
public record TransitionRule(JobStatus from, JobAction action, JobStatus to, Set<ActorRole> roles, JobAction followUp) {

    public TransitionRule {
        roles = Set.copyOf(roles);
    }
}
