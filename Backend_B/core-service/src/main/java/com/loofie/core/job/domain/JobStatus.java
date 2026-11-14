package com.loofie.core.job.domain;

/** Job states from spec F5. DRAFT_BRIEF lives in Backend A and only appears as the first log entry's from-state. */
public enum JobStatus {
    DRAFT_BRIEF,
    AWAITING_PAYMENT,
    PAID,
    CREATOR_INVITED,
    CREATOR_ACCEPTED,
    DECLINED,
    PRODUCT_SHIPPED,
    DELIVERY_ISSUE,
    PRODUCT_RECEIVED,
    IN_PRODUCTION,
    DRAFT_SUBMITTED,
    IN_REVIEW,
    REVISION_REQUESTED,
    APPROVED,
    DISPUTED,
    REPLACEMENT_NEEDED,
    REFUNDED,
    PAYOUT_PENDING,
    PAID_OUT,
    CLOSED,
    CANCELLED;

    public boolean isTerminal() {
        return this == CLOSED || this == REFUNDED || this == CANCELLED;
    }

    /** Whether a job row can be in this state. DRAFT_BRIEF is never stored in core. */
    public boolean isStorable() {
        return this != DRAFT_BRIEF;
    }
}
