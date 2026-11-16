package com.loofie.core.job.domain;

/**
 * Fixed reason codes for disputes and drop-outs. Sent to the AI feedback log instead of free text
 * (free text can hold personal details). New codes are added to loofie-ai/docs/DECISIONS.md first.
 * The creator reliability score counts only {@link FaultParty#CREATOR} codes.
 */
public enum ReasonCode {
    FALSE_CLAIM(FaultParty.CREATOR),
    CREATOR_UNRESPONSIVE(FaultParty.CREATOR),
    CREATOR_WITHDREW(FaultParty.CREATOR),
    BRAND_LATE_SHIP(FaultParty.BRAND),
    WRONG_ITEM_SENT(FaultParty.BRAND),
    /** Outer box fine, product broken, leaking, expired or badly packed inside (DECISIONS.md section 5). */
    DEFECTIVE_PRODUCT(FaultParty.BRAND),
    COURIER_LOST(FaultParty.COURIER),
    DAMAGED_IN_TRANSIT(FaultParty.COURIER),
    OTHER(FaultParty.NONE);

    public enum FaultParty { CREATOR, BRAND, COURIER, NONE }

    private final FaultParty fault;

    ReasonCode(FaultParty fault) {
        this.fault = fault;
    }

    public FaultParty fault() {
        return fault;
    }
}
