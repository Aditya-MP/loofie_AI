package com.loofie.core.job.app;

import java.time.Instant;

import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.DeliverySource;
import com.loofie.core.job.domain.ReasonCode;

/**
 * Extra input some actions need. All fields are optional at this level; the engine checks which
 * ones an action requires.
 *
 * @param courier          MARK_SHIPPED / RESHIP: courier name; required for "Other", else taken from the courier table
 * @param trackingNumber   MARK_SHIPPED / RESHIP: the courier's tracking (AWB) number; not for local delivery
 * @param photoRef         CONFIRM_RECEIVED: storage key of the product photo, {@code receipts/<uuid>.<jpg|jpeg|png|heic|webp>}
 * @param issueType        REPORT_DELIVERY_ISSUE: what went wrong
 * @param reasonCode       ops only: fixed reason code for the AI log (never free text)
 * @param deliveredAt      COURIER_DELIVERED: when the courier delivered (default now); never in the future,
 *                         never before the shipment
 * @param deliverySource   COURIER_DELIVERED: what the fact is based on (required from ops)
 * @param shipmentAttempt  CONFIRM_RECEIVED: which shipment arrived (default the latest); matters after a reship
 * @param courierCode      MARK_SHIPPED / RESHIP: a code from the courier table (DELHIVERY, LOCAL, OTHER...)
 * @param trackingLink     MARK_SHIPPED / RESHIP with "Other": an https tracking link
 * @param handoverPhotoRef MARK_SHIPPED / RESHIP with local delivery: {@code handovers/<uuid>.<jpg|jpeg|png|heic|webp>}
 * @param evidenceRef      REPORT_DELIVERY_ISSUE after receipt: {@code damage/<uuid>.<jpg|...|mp4|mov>}
 * @param draftId          SUBMIT_DRAFT / RESUBMIT / PREVIEW_READY: the checked video (set by the F7 media worker only;
 *                         never from a request, so a draft reaches the brand only after its checks)
 */
public record TransitionDetails(String courier, String trackingNumber, String photoRef, DeliveryIssueType issueType,
                                ReasonCode reasonCode, Instant deliveredAt, DeliverySource deliverySource,
                                Integer shipmentAttempt, String courierCode, String trackingLink,
                                String handoverPhotoRef, String evidenceRef, java.util.UUID draftId) {

    public static final TransitionDetails NONE = new TransitionDetails(null, null, null, null, null);

    public TransitionDetails(String courier, String trackingNumber, String photoRef, DeliveryIssueType issueType,
                             ReasonCode reasonCode) {
        this(courier, trackingNumber, photoRef, issueType, reasonCode, null, null, null);
    }

    public TransitionDetails(String courier, String trackingNumber, String photoRef, DeliveryIssueType issueType,
                             ReasonCode reasonCode, Instant deliveredAt, DeliverySource deliverySource,
                             Integer shipmentAttempt) {
        this(courier, trackingNumber, photoRef, issueType, reasonCode, deliveredAt, deliverySource, shipmentAttempt,
                null, null, null, null);
    }

    /** Everything a request may carry (the HTTP endpoint): never a draft id. */
    public TransitionDetails(String courier, String trackingNumber, String photoRef, DeliveryIssueType issueType,
                             ReasonCode reasonCode, Instant deliveredAt, DeliverySource deliverySource,
                             Integer shipmentAttempt, String courierCode, String trackingLink,
                             String handoverPhotoRef, String evidenceRef) {
        this(courier, trackingNumber, photoRef, issueType, reasonCode, deliveredAt, deliverySource, shipmentAttempt,
                courierCode, trackingLink, handoverPhotoRef, evidenceRef, null);
    }

    /** F7: the video that passed its checks (media worker only). */
    public static TransitionDetails draft(java.util.UUID draftId) {
        return new TransitionDetails(null, null, null, null, null, null, null, null, null, null, null, null,
                java.util.Objects.requireNonNull(draftId));
    }

    /** Shipping with a courier name only (matched against the courier table by name). */
    public static TransitionDetails shipment(String courier, String trackingNumber) {
        return new TransitionDetails(courier, trackingNumber, null, null, null);
    }

    /** Shipping with a courier from the table. */
    public static TransitionDetails shipVia(String courierCode, String trackingNumber) {
        return new TransitionDetails(null, trackingNumber, null, null, null, null, null, null, courierCode, null, null,
                null);
    }

    /** Local delivery: no tracking number, a handover photo instead. */
    public static TransitionDetails localDelivery(String handoverPhotoRef) {
        return new TransitionDetails(null, null, null, null, null, null, null, null, "LOCAL", null, handoverPhotoRef,
                null);
    }

    /** A courier that is not in the list: its name and a tracking link. */
    public static TransitionDetails otherCourier(String name, String trackingNumber, String trackingLink) {
        return new TransitionDetails(name, trackingNumber, null, null, null, null, null, null, "OTHER", trackingLink,
                null, null);
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

    /** Damage found after "Received", with the photo or video that shows it. */
    public static TransitionDetails damageAfterReceipt(String evidenceRef) {
        return new TransitionDetails(null, null, null, DeliveryIssueType.DAMAGED, null, null, null, null, null, null,
                null, evidenceRef);
    }

    public static TransitionDetails reason(ReasonCode code) {
        return new TransitionDetails(null, null, null, null, code);
    }
}
