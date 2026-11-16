package com.loofie.core.job.app;

import java.time.Instant;
import java.util.UUID;

/**
 * What the timer does when a replacement window runs out: money moves only after approval, so instead of refunding,
 * it asks for a refund. Implemented by the refund feature (the job feature does not depend on it).
 */
public interface TimerRefunds {

    /** Opens a refund request for a job whose replacement window has passed (Loofie's own failure). */
    void replacementExpired(UUID jobId, Instant now);
}
