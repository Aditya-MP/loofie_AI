package com.loofie.core.job.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One creator x one deliverable. State changes only through {@code JobTransitionService}
 * (enforced by an ArchUnit rule on {@link #moveTo}).
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "brand_org_id", nullable = false, updatable = false)
    private UUID brandOrgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;

    @Column(name = "digital_only", nullable = false, updatable = false)
    private boolean digitalOnly;

    @Column(name = "creator_fee_paise", nullable = false)
    private long creatorFeePaise;

    @Column(name = "free_revision_used", nullable = false)
    private boolean freeRevisionUsed;

    @Column(name = "creator_extension_used", nullable = false)
    private boolean creatorExtensionUsed;

    @Column(name = "accept_due_at")
    private Instant acceptDueAt;

    @Column(name = "receipt_prompt_at")
    private Instant receiptPromptAt;

    @Column(name = "receipt_fallback_at")
    private Instant receiptFallbackAt;

    @Column(name = "production_due_at")
    private Instant productionDueAt;

    @Column(name = "review_due_at")
    private Instant reviewDueAt;

    @Column(name = "replacement_due_at")
    private Instant replacementDueAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Job() {
        // for JPA
    }

    /** A new job enters core already in AWAITING_PAYMENT; the DRAFT_BRIEF step is logged by intake. */
    public static Job forNewOrder(UUID id, UUID orderId, UUID brandOrgId, boolean digitalOnly, long creatorFeePaise, Instant now) {
        if (creatorFeePaise < 0) {
            throw new IllegalArgumentException("creatorFeePaise must be >= 0");
        }
        Job job = new Job();
        job.id = Objects.requireNonNull(id);
        job.orderId = Objects.requireNonNull(orderId);
        job.brandOrgId = Objects.requireNonNull(brandOrgId);
        job.status = JobStatus.AWAITING_PAYMENT;
        job.digitalOnly = digitalOnly;
        job.creatorFeePaise = creatorFeePaise;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    /** Only the transition engine may call this. */
    public void moveTo(JobStatus to, Instant now) {
        if (!to.isStorable()) {
            throw new IllegalArgumentException(to + " cannot be stored on a job");
        }
        this.status = to;
        this.updatedAt = now;
    }

    public void markFreeRevisionUsed() {
        this.freeRevisionUsed = true;
    }

    public void markCreatorExtensionUsed() {
        this.creatorExtensionUsed = true;
    }

    /** Pushes the running production deadline back. Only {@link JobDeadlines} calls this. */
    void extendProductionDue(java.time.Duration by, Instant now) {
        if (productionDueAt == null) {
            throw new IllegalStateException("Job " + id + " has no production deadline to extend");
        }
        // An ops extension after the deadline has passed counts from now, not from the old deadline.
        Instant base = productionDueAt.isAfter(now) ? productionDueAt : now;
        this.productionDueAt = base.plus(by);
    }

    public boolean isCreatorExtensionUsed() {
        return creatorExtensionUsed;
    }

    void setDeadlines(Instant acceptDueAt, Instant receiptPromptAt, Instant receiptFallbackAt,
                      Instant productionDueAt, Instant reviewDueAt, Instant replacementDueAt) {
        this.acceptDueAt = acceptDueAt;
        this.receiptPromptAt = receiptPromptAt;
        this.receiptFallbackAt = receiptFallbackAt;
        this.productionDueAt = productionDueAt;
        this.reviewDueAt = reviewDueAt;
        this.replacementDueAt = replacementDueAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getBrandOrgId() {
        return brandOrgId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public boolean isDigitalOnly() {
        return digitalOnly;
    }

    public long getCreatorFeePaise() {
        return creatorFeePaise;
    }

    public boolean isFreeRevisionUsed() {
        return freeRevisionUsed;
    }

    public Instant getAcceptDueAt() {
        return acceptDueAt;
    }

    public Instant getReceiptPromptAt() {
        return receiptPromptAt;
    }

    public Instant getReceiptFallbackAt() {
        return receiptFallbackAt;
    }

    public Instant getProductionDueAt() {
        return productionDueAt;
    }

    public Instant getReviewDueAt() {
        return reviewDueAt;
    }

    public Instant getReplacementDueAt() {
        return replacementDueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
