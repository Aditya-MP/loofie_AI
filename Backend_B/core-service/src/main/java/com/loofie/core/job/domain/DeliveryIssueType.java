package com.loofie.core.job.domain;

/** What the creator reported when the product did not arrive in good order (F6). */
public enum DeliveryIssueType {
    NOT_RECEIVED,
    DAMAGED,
    WRONG_ITEM;

    /** Default reason code when the creator withdraws after this issue; ops can choose another one. */
    public ReasonCode withdrawalReason() {
        return switch (this) {
            case NOT_RECEIVED -> ReasonCode.COURIER_LOST;
            case DAMAGED -> ReasonCode.DAMAGED_IN_TRANSIT;
            case WRONG_ITEM -> ReasonCode.WRONG_ITEM_SENT;
        };
    }
}
