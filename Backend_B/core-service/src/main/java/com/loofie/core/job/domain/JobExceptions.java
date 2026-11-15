package com.loofie.core.job.domain;

import java.util.UUID;

/** Errors raised by the transition engine. All are unchecked so the transaction rolls back. */
public final class JobExceptions {

    private JobExceptions() {
    }

    public static class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(UUID jobId) {
            super("Job not found: " + jobId);
        }
    }

    /** The (state, action) pair is not in the transition table. */
    public static class IllegalTransitionException extends RuntimeException {
        private final JobStatus from;
        private final JobAction action;

        public IllegalTransitionException(JobStatus from, JobAction action) {
            super("Illegal transition: " + action + " is not allowed from " + from);
            this.from = from;
            this.action = action;
        }

        public JobStatus from() {
            return from;
        }

        public JobAction action() {
            return action;
        }
    }

    /** The transition exists but this actor may not perform it (wrong role, wrong creator, wrong brand). */
    public static class ActorNotAllowedException extends RuntimeException {
        public ActorNotAllowedException(String message) {
            super(message);
        }
    }

    /** The caller acted on an old view of the job: it has changed since (expectedVersion mismatch). */
    public static class StaleJobVersionException extends RuntimeException {
        private final long currentVersion;

        public StaleJobVersionException(UUID jobId, long expected, long current) {
            super("Job " + jobId + " has changed since you loaded it (version " + expected + ", now " + current
                    + "); reload and try again");
            this.currentVersion = current;
        }

        public long currentVersion() {
            return currentVersion;
        }
    }

    /** A list or search request the service will not run as asked (bad cursor, too deep a page). */
    public static class InvalidQueryException extends RuntimeException {
        public InvalidQueryException(String message) {
            super(message);
        }
    }

    /** The transition exists and the actor is allowed, but a business rule blocks it right now. */
    public static class TransitionGuardException extends RuntimeException {
        public TransitionGuardException(String message) {
            super(message);
        }
    }
}
