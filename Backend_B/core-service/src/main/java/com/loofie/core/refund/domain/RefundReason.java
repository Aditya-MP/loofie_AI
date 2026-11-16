package com.loofie.core.refund.domain;

/** Why money goes back (a code, never free text; ops notes stay in core). */
public enum RefundReason {
    /** The brand cancelled. */
    BRAND_CANCELLED,
    /** Loofie found no creator in the replacement window (Loofie's own failure: the fee is refunded too). */
    LOOFIE_NO_CREATOR,
    /** Ops upheld the brand's dispute. */
    DISPUTE_UPHELD,
    /** The brand shipped late and the creator dropped out. */
    BRAND_LATE_SHIP,
    OTHER
}
