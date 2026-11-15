package com.loofie.core.job.app;

import java.time.Instant;

import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.DeliverySource;
import com.loofie.core.job.domain.ReasonCode;

/**
 * Extra input some actions need. All fields are optional at this level; the engine checks which
 * ones an action requires.
 *
 * @param courier         MARK_SHIPPED / RESHIP: courier name, e.g. "Delhivery"
 * @param trackingNumber  MARK_SHIPPED / RESHIP: the courier's tracking (AWB) number
 * @param photoRef        CONFIRM_RECEIVED: storage key of the product photo, {@code receipts/<uuid>.<jpg|jpeg|png|heic|webp>}
 * @param issueType       REPORT_DELIVERY_ISSUE: what went wrong
 * @param reasonCode      ops only: fixed reason code for the AI log (never free text)
 * @param deliveredAt     COURIER_DELIVERED: when the courier delivered (default now); never in the future,
 *                        never before the shipment
 * @param deliverySource  COURIER_DELIVERED: what the fact is based on (required from ops)
 * @param shipmentAttempt CONFIRM_RECEIVED: which shipment arrived (default the latest); matters after a reship
 */
public record TransitionDetails(String courier, String trackingNumber, String photoRef, DeliveryIssueType issueType,
                                ReasonCode reasonCode, Instant deliveredAt, DeliverySource deliverySource,
                                Integer shipmentAttempt) {

    public static final TransitionDetails NONE = new TransitionDetails(null, null, null, null, null);

    public TransitionDetails(String courier, String trackingNumber, String photoRef, DeliveryIssueType issueType,
                             ReasonCode reasonCode) {
        this(courier, trackingNumber, photoRef, issueType, reasonCode, null, null, null);
    }

    public static TransitionDetails shipment(String courier, String trackingNumber) {
        return new TransitionDetails(courier, trackingNumber, null, null, null);
    }

    public static TransitionDetails photo(String photoRef) {
        return new TransitionDetails(null, null, photoRef, null, null);
    }

    /** "Received" for a specific shipment attempt (after a reship, the first parcel may turn up). */
    public static TransitionDetails received(String photoRef, int shipmentAttempt) {
        return new TransitionDetails(null, null, photoRef, null, null, null, null, shipmentAttempt);
    }

    public static TransitionDetails courierDelivered(DeliverySource source, Instant deliveredAt) {
        return new TransitionDetails(null, null, null, null, null, deliveredAt, source, null);
    }

    public static TransitionDetails issue(DeliveryIssueType type) {
        return new TransitionDetails(null, null, null, type, null);
    }

    public static TransitionDetails reason(ReasonCode code) {
        return new TransitionDetails(null, null, null, null, code);
    }
}
