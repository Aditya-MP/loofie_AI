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

    public void markCourierDelivered(Instant at, UUID by) {
        this.courierDeliveredAt = at;
        this.courierDeliveredBy = by;
    }

    public void markReceived(Instant at, String photoRef) {
        this.receivedAt = at;
        this.receiptPhotoRef = photoRef;
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
