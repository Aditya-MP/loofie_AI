package com.loofie.core.draft.app;

import java.time.Duration;
import java.util.Set;

import com.loofie.core.job.domain.JobStatus;

/** F7 numbers in one place (plan D4, D7, D9). */
final class DraftRules {

    /** States in which the creator may upload a video. */
    static final Set<JobStatus> UPLOAD_STATES = Set.of(JobStatus.IN_PRODUCTION, JobStatus.REVISION_REQUESTED);
    /** States after approval: the brand may download the full-quality video. */
    static final Set<JobStatus> APPROVED_STATES = Set.of(JobStatus.APPROVED, JobStatus.PAYOUT_PENDING,
            JobStatus.PAID_OUT, JobStatus.CLOSED);

    /** Uploads per job, failed ones included: enough for honest retries, not a storage sink. */
    static final int MAX_UPLOADS_PER_JOB = 20;
    /** A failed video is kept a week so the creator can see what was wrong, then removed. */
    static final Duration FAILED_KEEP = Duration.ofDays(7);
    /** D9: after the ad-rights period, 30 more days, then the videos are removed. */
    static final Duration AFTER_AD_RIGHTS = Duration.ofDays(30);
    /** D9 when the brief did not say how long the ad rights last. */
    static final int DEFAULT_AD_RIGHTS_DAYS = 90;
    /** Videos of a job that never reached approval are removed this long after upload (once the job ended). */
    static final Duration UNAPPROVED_KEEP = Duration.ofDays(180);
    /** D7. */
    static final Duration RATING_WINDOW = Duration.ofDays(7);
    /** Watching a 90 s preview, pausing, scrolling back: the link outlives one viewing. */
    static final Duration PREVIEW_LINK_TTL = Duration.ofMinutes(30);
    /** The full-quality download link (audited). */
    static final Duration MASTER_LINK_TTL = Duration.ofMinutes(15);
    static final int MAX_NOTE = 500;

    private DraftRules() {
    }
}
