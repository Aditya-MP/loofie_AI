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

    @Column(name = "tracking_number", nullable = false, updatable = false, length = 64)
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

    @Version
    private Long version;

    protected Shipment() {
        // for JPA
    }

    public static Shipment create(UUID id, UUID jobId, UUID assignmentId, int attempt, String courier,
                                  String trackingNumber, Instant shippedAt, UUID shippedBy) {
        Shipment s = new Shipment();
        s.id = Objects.requireNonNull(id);
        s.jobId = Objects.requireNonNull(jobId);
        s.assignmentId = Objects.requireNonNull(assignmentId);
        s.attempt = attempt;
        s.courier = requireText(courier, "courier");
        s.trackingNumber = requireText(trackingNumber, "trackingNumber");
        s.shippedAt = Objects.requireNonNull(shippedAt);
        s.shippedBy = Objects.requireNonNull(shippedBy);
        return s;
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
