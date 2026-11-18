package com.loofie.core.draft.app;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.loofie.core.draft.domain.Brief;
import com.loofie.core.draft.domain.DraftStatus;
import com.loofie.core.draft.domain.VideoChecks;
import com.loofie.core.job.domain.JobStatus;

/** What the F7 screens get. */
public final class DraftViews {

    private DraftViews() {
    }

    /** What the checks found (display size after rotation). */
    public record Probe(int width, int height, double seconds, boolean hasAudio) {
    }

    public record Draft(UUID id, int attempt, String kind, DraftStatus status, List<VideoChecks.Failure> failures,
                        Probe probe, Instant submittedAt, Instant readyAt) {
    }

    public record Limits(long maxBytes, List<String> types, int minSeconds, int maxSeconds, int minWidth,
                         int minHeight) {
    }

    /** @param fromBrief false = the default checklist and 15-90 s (the job has no brief copy yet) */
    public record BriefView(List<Brief.ChecklistItem> checklist, int minSeconds, int maxSeconds, boolean fromBrief) {
    }

    public record Answer(String key, String label, boolean ok, String note) {
    }

    /** The brand's last decision on a video. */
    public record Review(UUID draftId, String decision, List<Answer> answers, String note, Instant reviewedAt) {
    }

    /** The creator's screen: upload limits, the checklist the brand will use, her videos, what to fix. */
    public record CreatorDrafts(UUID jobId, JobStatus jobStatus, boolean canUpload, String uploadNote, Limits limits,
                                BriefView brief, Review revisionRequested, List<Draft> drafts) {
    }

    /**
     * The brand's screen. {@code previewUrl} only when a video is ready; the full-quality file is never linked here
     * (it has its own audited endpoint, after approval).
     */
    public record BrandDraft(UUID jobId, JobStatus jobStatus, long jobVersion, Draft current, URI previewUrl,
                             Instant previewUrlExpiresAt, BriefView brief, boolean freeRevisionUsed,
                             Instant reviewDueAt, boolean masterAvailable, List<Review> reviews, Integer rating,
                             Instant ratingOpenUntil) {
    }

    public record Link(URI url, Instant expiresAt) {
    }
}
