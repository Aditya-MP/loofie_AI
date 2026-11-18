package com.loofie.core.draft.domain;

/**
 * One uploaded video's life. Only READY is ever shown to the brand.
 * <ul>
 *   <li>CHECKING: uploaded, waiting for the technical checks;</li>
 *   <li>PREVIEWING: checks passed, the watermarked preview is being made;</li>
 *   <li>READY: the preview exists and the job moved to review (the brand sees this one);</li>
 *   <li>FAILED: a check failed (the creator sees why) or the file could not be processed;</li>
 *   <li>SUPERSEDED: a newer video replaced it, or the job moved on while it was processed;</li>
 *   <li>PURGED: files removed by retention (D9); the row stays as the record.</li>
 * </ul>
 */
public enum DraftStatus {
    CHECKING, PREVIEWING, READY, FAILED, SUPERSEDED, PURGED;

    public boolean inFlight() {
        return this == CHECKING || this == PREVIEWING;
    }
}
