package com.loofie.core.job.domain;

/** Named actions that move a job between states. Each one maps to a row in {@link TransitionTable}. */
public enum JobAction {
    SUBMIT_ORDER,
    PAYMENT_CONFIRMED,
    INVITE,
    ACCEPT,
    DECLINE,
    ACCEPT_TIMEOUT,
    APPROVE_SWAP,
    MARK_SHIPPED,
    START_DIGITAL,
    CONFIRM_RECEIVED,
    RECEIPT_FALLBACK,
    START_PRODUCTION,
    SUBMIT_DRAFT,
    PRODUCTION_EXPIRED,
    PREVIEW_READY,
    APPROVE,
    AUTO_APPROVE,
    REQUEST_REVISION,
    RESUBMIT,
    REJECT,
    RESOLVE_APPROVE,
    RESOLVE_REPLACE,
    RESOLVE_REFUND,
    REPLACEMENT_EXPIRED,
    PAYOUT_QUEUED,
    PAYOUT_COMPLETED,
    CLOSE,
    CANCEL
}
