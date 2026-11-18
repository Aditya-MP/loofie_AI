package com.loofie.core.job.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the engine asks F7 about videos (implemented by the draft feature, so the engine never reads its tables).
 * A draft reaches the brand only through the media worker: checks passed, preview made.
 */
public interface DraftGate {

    /**
     * When the creator uploaded this video, if it passed every check, has its preview, belongs to this job and was not
     * delivered yet. Empty otherwise: then SUBMIT_DRAFT / RESUBMIT / PREVIEW_READY are refused.
     */
    Optional<Instant> deliverable(UUID jobId, UUID draftId);

    /** A video uploaded before {@code deadline} is still being checked: the production deadline waits for it. */
    boolean inFlightSince(UUID jobId, Instant deadline);

    /** The brand approved (or auto-approval, or ops resolved a dispute as approved): retention and rating start. */
    void approved(UUID jobId, Instant at);
}
