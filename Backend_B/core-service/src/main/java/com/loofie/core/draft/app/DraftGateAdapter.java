package com.loofie.core.draft.app;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.loofie.core.draft.infra.BriefRepository;
import com.loofie.core.draft.infra.DraftRepository;
import com.loofie.core.job.domain.DraftGate;

/** Answers the engine's questions about videos (the job package never reads draft tables). */
@Component
class DraftGateAdapter implements DraftGate {

    private final DraftRepository drafts;
    private final BriefRepository briefs;

    DraftGateAdapter(DraftRepository drafts, BriefRepository briefs) {
        this.drafts = drafts;
        this.briefs = briefs;
    }

    @Override
    public Optional<Instant> deliverable(UUID jobId, UUID draftId) {
        return drafts.deliverable(jobId, draftId);
    }

    @Override
    public boolean inFlightSince(UUID jobId, Instant deadline) {
        return drafts.inFlightSince(jobId, deadline);
    }

    /** D9: the delivered videos are kept for the ad-rights period plus 30 days. */
    @Override
    public void approved(UUID jobId, Instant at) {
        int days = briefs.find(jobId).map(s -> s.brief().adRightsDays()).filter(d -> d != null)
                .orElse(DraftRules.DEFAULT_AD_RIGHTS_DAYS);
        drafts.approved(jobId, at, at.plus(java.time.Duration.ofDays(days)).plus(DraftRules.AFTER_AD_RIGHTS));
    }
}
