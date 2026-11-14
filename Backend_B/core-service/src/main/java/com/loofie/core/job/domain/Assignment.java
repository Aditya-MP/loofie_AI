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

/** Which creator is on a job. Replacing a creator closes this and opens a new one on the same job. */
@Entity
@Table(name = "assignments")
public class Assignment {

    public enum Status { ACTIVE, CLOSED }

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private UUID creatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "close_reason", length = 64)
    private String closeReason;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private Long version;

    protected Assignment() {
        // for JPA
    }

    public static Assignment open(UUID id, UUID jobId, UUID creatorId, Instant now) {
        Assignment a = new Assignment();
        a.id = Objects.requireNonNull(id);
        a.jobId = Objects.requireNonNull(jobId);
        a.creatorId = Objects.requireNonNull(creatorId);
        a.status = Status.ACTIVE;
        a.openedAt = now;
        return a;
    }

    public void close(String reason, Instant now) {
        if (status == Status.CLOSED) {
            throw new IllegalStateException("Assignment " + id + " is already closed");
        }
        this.status = Status.CLOSED;
        this.closeReason = reason;
        this.closedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public Status getStatus() {
        return status;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
