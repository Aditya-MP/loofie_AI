package com.loofie.core.creator.domain;

import java.time.Instant;
import java.util.UUID;

/** A screenshot a creator uploaded: Instagram Insights (manual numbers) or proof of a past brand collaboration. */
public record Evidence(UUID id, UUID creatorId, String uploadKey, Kind kind, Status status, String reviewNote,
                       UUID reviewedBy, Instant reviewedAt, Instant createdAt) {

    public enum Kind { INSIGHTS, COLLAB_PROOF }

    /** REMOVED: the creator took it down (the file is deleted from storage). */
    public enum Status { PENDING, ACCEPTED, REJECTED, REMOVED }

    /** Still counts as support for her numbers. */
    public boolean live() {
        return status == Status.PENDING || status == Status.ACCEPTED;
    }
}
