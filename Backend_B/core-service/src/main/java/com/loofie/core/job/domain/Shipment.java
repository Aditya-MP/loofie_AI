package com.loofie.core.job.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One shipment attempt for a job (F6). A reship adds a new row, so the courier evidence for every
 * attempt is kept: it is what settles a later "not received" dispute.
 */
@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "assignment_id", nullable = false, updatable = false)
    private UUID assignmentId;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Column(nullable = false, updatable = false, length = 64)
    private String courier;

    @Column(name = "tracking_number", updatable = false, length = 64) // null only for local delivery (handover photo)
    private String trackingNumber;

    @Column(name = "shipped_at", nullable = false, updatable = false)
    private Instant shippedAt;

    @Column(name = "shipped_by", nullable = false, updatable = false)
    private UUID shippedBy;

    @Column(name = "courier_delivered_at")
    private Instant courierDeliveredAt;

    @Column(name = "courier_delivered_by")
    private UUID courierDeliveredBy;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "courier_delivered_source", length = 16)
    private DeliverySource courierDeliveredSource;

    @Column(name = "courier_delivered_recorded_at")
    private Instant courierDeliveredRecordedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "receipt_photo_ref", length = 512)
    private String receiptPhotoRef;

    @Column(name = "courier_code", updatable = false, length = 32)
    private String courierCode;

    @Column(name = "tracking_url", updatable = false, length = 300)
    private String trackingUrl;

    @Column(name = "handover_photo_ref", updatable = false, length = 512)
    private String handoverPhotoRef;

    @Column(name = "tracking_norm", updatable = false, length = 64)
    private String trackingNorm;

    @Column(name = "tracking_reuse", updatable = false, length = 32)
    private String trackingReuse;

    @Column(name = "damage_reported_at")
    private Instant damageReportedAt;

    @Column(name = "damage_evidence_ref", length = 512)
    private String damageEvidenceRef;

    @Version
    private Long version;

    protected Shipment() {
        // for JPA
    }

    public static Shipment create(UUID id, UUID jobId, UUID assignmentId, int attempt, String courier,
                                  String trackingNumber, Instant shippedAt, UUID shippedBy) {
        return create(id, jobId, assignmentId, attempt, new Parcel(null, courier, trackingNumber, null, null, null),
                shippedAt, shippedBy);
    }

    /**
     * What the brand entered when shipping, already checked by the engine against the courier's kind.
     *
     * @param trackingReuse null, or "SAME_BRAND_OTHER_CREATOR" when the number is on another creator's job too
     */
    public record Parcel(String courierCode, String courierName, String trackingNumber, String trackingUrl,
                         String handoverPhotoRef, String trackingReuse) {
    }

    public static Shipment create(UUID id, UUID jobId, UUID assignmentId, int attempt, Parcel parcel, Instant shippedAt,
                                  UUID shippedBy) {
        Shipment s = new Shipment();
        s.id = Objects.requireNonNull(id);
        s.jobId = Objects.requireNonNull(jobId);
        s.assignmentId = Objects.requireNonNull(assignmentId);
        s.attempt = attempt;
        s.courierCode = parcel.courierCode();
        s.courier = requireText(parcel.courierName(), "courier");
        s.trackingNumber = parcel.trackingNumber() == null ? null : requireText(parcel.trackingNumber(), "trackingNumber");
        s.trackingNorm = Courier.normaliseTrackingNumber(s.trackingNumber);
        s.trackingUrl = parcel.trackingUrl();
        s.handoverPhotoRef = parcel.handoverPhotoRef();
        s.trackingReuse = parcel.trackingReuse();
        if (s.trackingNumber == null && s.handoverPhotoRef == null) {
            throw new IllegalArgumentException("A shipment needs a tracking number or a handover photo");
        }
        if (s.handoverPhotoRef != null && !isHandoverPhotoKey(s.handoverPhotoRef)) {
            throw new IllegalArgumentException("Not a handover photo key: " + s.handoverPhotoRef);
        }
        s.shippedAt = Objects.requireNonNull(shippedAt);
        s.shippedBy = Objects.requireNonNull(shippedBy);
        return s;
    }

    /** Damage found after receipt: when, and the photo or video that shows it. */
    public void markDamageReported(Instant at, String evidenceRef) {
        if (evidenceRef != null && !isDamageEvidenceKey(evidenceRef)) {
            throw new IllegalArgumentException("Not a damage evidence key: " + evidenceRef);
        }
        this.damageReportedAt = Objects.requireNonNull(at);
        this.damageEvidenceRef = evidenceRef;
    }

    // Same patterns as the CHECK constraints (V10). Storage keys we issue only, never URLs or free text.
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public static final java.util.regex.Pattern HANDOVER_PHOTO_KEY = java.util.regex.Pattern.compile(
            "^handovers/" + UUID_RE + "\\.(jpg|jpeg|png|heic|webp)$");
    public static final java.util.regex.Pattern DAMAGE_EVIDENCE_KEY = java.util.regex.Pattern.compile(
            "^damage/" + UUID_RE + "\\.(jpg|jpeg|png|heic|webp|mp4|mov)$");

    public static boolean isHandoverPhotoKey(String value) {
        return value != null && HANDOVER_PHOTO_KEY.matcher(value).matches();
    }

    public static boolean isDamageEvidenceKey(String value) {
        return value != null && DAMAGE_EVIDENCE_KEY.matcher(value).matches();
    }

    /**
     * @param deliveredAt when the courier delivered (the creator's 24h window counts from here)
     * @param recordedAt  when we were told; differs when ops records it late
     */
    public void markCourierDelivered(Instant deliveredAt, UUID by, DeliverySource source, Instant recordedAt) {
        this.courierDeliveredAt = Objects.requireNonNull(deliveredAt);
        this.courierDeliveredBy = by;
        this.courierDeliveredSource = Objects.requireNonNull(source);
        this.courierDeliveredRecordedAt = Objects.requireNonNull(recordedAt);
    }

    public void markReceived(Instant at, String photoRef) {
        if (!isReceiptPhotoKey(photoRef)) {
            throw new IllegalArgumentException("Not a receipt photo key: " + photoRef);
        }
        this.receivedAt = at;
        this.receiptPhotoRef = photoRef;
    }

    /**
     * Receipt photos are referenced only by a storage key we issue: {@code receipts/<uuid>.<jpg|jpeg|png|heic|webp>}.
     * Never a URL or free text: ops opens these, so anything else is a stored-XSS or phishing risk.
     * The same pattern is a CHECK constraint on the table (V7).
     */
    public static final java.util.regex.Pattern RECEIPT_PHOTO_KEY = java.util.regex.Pattern.compile(
            "^receipts/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|heic|webp)$");

    public static boolean isReceiptPhotoKey(String value) {
        return value != null && RECEIPT_PHOTO_KEY.matcher(value).matches();
    }

    public Instant getDamageReportedAt() {
        return damageReportedAt;
    }

    public boolean isReceived() {
        return receivedAt != null;
    }

    public DeliverySource getCourierDeliveredSource() {
        return courierDeliveredSource;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getCourier() {
        return courier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getCourierCode() {
        return courierCode;
    }

    /** The "Other" courier's own tracking link; null for listed couriers (their pattern builds it). */
    public String getTrackingUrl() {
        return trackingUrl;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getCourierDeliveredAt() {
        return courierDeliveredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getReceiptPhotoRef() {
        return receiptPhotoRef;
    }
}
