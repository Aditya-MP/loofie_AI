package com.loofie.core.identity.domain;

import java.time.Duration;

/** Failures the login and account screens show to the person; each maps to one clear HTTP answer. */
public final class IdentityExceptions {

    private IdentityExceptions() {
    }

    /** 400: wrong code; the same challenge may be tried again while tries are left. */
    public static class InvalidCodeException extends RuntimeException {
        private final int attemptsLeft;

        public InvalidCodeException(int attemptsLeft) {
            super(attemptsLeft > 0 ? "That code is not right. " + attemptsLeft + " tries left."
                    : "That code is not right. Request a new code.");
            this.attemptsLeft = attemptsLeft;
        }

        private InvalidCodeException(String message) {
            super(message);
            this.attemptsLeft = -1;
        }

        /** Authenticator and recovery codes: tries are limited per 15 minutes, not per code. */
        public static InvalidCodeException authenticator() {
            return new InvalidCodeException("That code is not right or was already used. Wait for the next code "
                    + "in your authenticator app, or use a recovery code.");
        }

        /** -1 for authenticator codes. */
        public int attemptsLeft() {
            return attemptsLeft;
        }
    }

    /** 400: expired, used, failed to send, or too many wrong tries. */
    public static class CodeExpiredException extends RuntimeException {
        public CodeExpiredException() {
            super("This code has expired or was already used. Request a new code.");
        }
    }

    /** 429. */
    public static class RateLimitedException extends RuntimeException {
        private final Duration retryAfter;

        public RateLimitedException(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /** 503: the SMS / email could not be sent, or sending is paused (spend cap, provider not set up). */
    public static class DeliveryUnavailableException extends RuntimeException {
        public DeliveryUnavailableException(String message) {
            super(message);
        }
    }

    /** 403, only after the person proved who they are. */
    public static class AccountSuspendedException extends RuntimeException {
        public AccountSuspendedException() {
            super("This account is suspended. Reply to the message we sent you, or write to support, to appeal.");
        }
    }

    /** 422: the request cannot be accepted as sent. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    /** 409: the phone or email already belongs to another account. */
    public static class ContactTakenException extends RuntimeException {
        public ContactTakenException(String message) {
            super(message);
        }
    }

    /** 409: another company already uses this GSTIN. */
    public static class GstinTakenException extends RuntimeException {
        public GstinTakenException() {
            super("This GSTIN already belongs to a company on Loofie. Ask that company's admin to invite you, or "
                    + "contact support if your companies really share one GSTIN.");
        }
    }

    /** 404: no such company, or not yours. */
    public static class CompanyNotFoundException extends RuntimeException {
        public CompanyNotFoundException() {
            super("No such company");
        }
    }

    /** 404. */
    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException() {
            super("No such account");
        }
    }

    /**
     * 409: the account cannot be deleted yet (open jobs, money not settled, the last admin of a team). The jobs, if any,
     * are listed so the person or ops can see what is in the way.
     */
    public static class DeletionBlockedException extends RuntimeException {
        private final java.util.List<java.util.UUID> jobIds;

        public DeletionBlockedException(String message, java.util.List<java.util.UUID> jobIds) {
            super(message);
            this.jobIds = java.util.List.copyOf(jobIds);
        }

        public java.util.List<java.util.UUID> jobIds() {
            return jobIds;
        }
    }
}
