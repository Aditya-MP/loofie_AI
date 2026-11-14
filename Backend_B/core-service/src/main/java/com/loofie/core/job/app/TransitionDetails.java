package com.loofie.core.job.app;

import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.ReasonCode;

/**
 * Extra input some actions need. All fields are optional at this level; the engine checks which
 * ones an action requires.
 *
 * @param courier        MARK_SHIPPED / RESHIP: courier name, e.g. "Delhivery"
 * @param trackingNumber MARK_SHIPPED / RESHIP: the courier's tracking (AWB) number
 * @param photoRef       CONFIRM_RECEIVED: reference to the product photo (an object-storage key once F7 exists)
 * @param issueType      REPORT_DELIVERY_ISSUE: what went wrong
 * @param reasonCode     ops only: fixed reason code for the AI log (never free text)
 */
public record TransitionDetails(String courier, String trackingNumber, String photoRef, DeliveryIssueType issueType,
                                ReasonCode reasonCode) {

    public static final TransitionDetails NONE = new TransitionDetails(null, null, null, null, null);

    public static TransitionDetails shipment(String courier, String trackingNumber) {
        return new TransitionDetails(courier, trackingNumber, null, null, null);
    }

    public static TransitionDetails photo(String photoRef) {
        return new TransitionDetails(null, null, photoRef, null, null);
    }

    public static TransitionDetails issue(DeliveryIssueType type) {
        return new TransitionDetails(null, null, null, type, null);
    }

    public static TransitionDetails reason(ReasonCode code) {
        return new TransitionDetails(null, null, null, null, code);
    }
}
