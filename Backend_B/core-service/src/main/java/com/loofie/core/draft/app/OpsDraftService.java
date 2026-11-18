package com.loofie.core.draft.app;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.draft.domain.Brief;
import com.loofie.core.draft.infra.BriefRepository;
import com.loofie.core.draft.infra.DraftRepository;
import com.loofie.core.draft.infra.ReviewRepository;
import com.loofie.core.identity.app.AuditLog;
import com.loofie.core.job.app.JobFacts;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.storage.Uploads;

/**
 * Ops and F7: enter the brief copy until Backend A sends it (D1), see every video of a job with its checks and the
 * brand's answers, and open a video (preview or full quality) for a dispute, always with a reason and audited.
 */
@Service
public class OpsDraftService {

    public record OpsDrafts(UUID jobId, DraftViews.BriefView brief, String briefSource, List<OpsDraft> drafts,
                            List<DraftViews.Review> reviews, Integer rating, String ratingComment) {
    }

    public record OpsDraft(DraftViews.Draft draft, UUID creatorId, int tries, String lastError, Instant approvedAt,
                           Instant deleteAfter) {
    }

    private final JobFacts jobs;
    private final DraftRepository drafts;
    private final BriefRepository briefs;
    private final ReviewRepository reviews;
    private final Uploads uploads;
    private final AuditLog audit;
    private final DraftMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DraftReadFence fence;

    OpsDraftService(DraftReadFence fence, JobFacts jobs, DraftRepository drafts, BriefRepository briefs, ReviewRepository reviews,
                    Uploads uploads, AuditLog audit, DraftMapper mapper, TransactionTemplate tx, Clock clock) {
        this.fence = fence;
        this.jobs = jobs;
        this.drafts = drafts;
        this.briefs = briefs;
        this.reviews = reviews;
        this.uploads = uploads;
        this.audit = audit;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    /** The brief copy (D1). Locked once a video was uploaded: the creator worked to it. */
    public DraftViews.BriefView setBrief(Actor actor, UUID jobId, Brief brief, String reason) {
        actor.requireRole(ActorRole.OPS, "Only ops enters a brief");
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5) {
            throw new TransitionGuardException("Say where the brief comes from (reason, at least 5 characters)");
        }
        jobs.forActor(actor, jobId);
        tx.executeWithoutResult(s -> {
            if (!drafts.forJob(jobId).isEmpty()) {
                throw new TransitionGuardException("A video was already uploaded against this brief; it can no "
                        + "longer change");
            }
            briefs.upsert(jobId, brief, "OPS", actor.userId(), clock.instant());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("jobId", jobId.toString());
            details.put("items", brief.checklist().size());
            details.put("minSeconds", brief.minSeconds());
            details.put("maxSeconds", brief.maxSeconds());
            details.put("adRightsDays", brief.adRightsDays());
            audit.record(actor, AuditLog.BRIEF_SET_BY_OPS, null, null, why, details);
        });
        return DraftMapper.brief(briefs.find(jobId).orElseThrow().brief());
    }

    public OpsDrafts view(Actor actor, UUID jobId) {
        actor.requireRole(ActorRole.OPS, "Ops only");
        return fence.read(() -> viewInTx(actor, jobId));
    }

    private OpsDrafts viewInTx(Actor actor, UUID jobId) {
        jobs.forActor(actor, jobId);
        BriefRepository.Stored stored = briefs.find(jobId).orElse(null);
        Brief brief = stored == null ? Brief.defaults() : stored.brief();
        ReviewRepository.Rating rating = reviews.rating(jobId).orElse(null);
        return new OpsDrafts(jobId, DraftMapper.brief(brief), stored == null ? null : stored.source(),
                drafts.forJob(jobId).stream().map(d -> new OpsDraft(mapper.draft(d), d.creatorId(), d.tries(),
                        d.lastError(), d.approvedAt(), d.deleteAfter())).toList(),
                reviews.forJob(jobId).stream().map(mapper::review).toList(),
                rating == null ? null : rating.stars(), rating == null ? null : rating.comment());
    }

    /** Opens one video for ops (a dispute, a complaint): a short link, with the reason in the audit log. */
    public DraftViews.Link open(Actor actor, UUID jobId, UUID draftId, boolean master, String reason) {
        actor.requireRole(ActorRole.OPS, "Ops only");
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5) {
            throw new TransitionGuardException("Say why you open this video (reason, at least 5 characters)");
        }
        JobFacts.Facts f = jobs.forActor(actor, jobId);
        DraftRepository.Draft d = drafts.find(draftId).filter(x -> x.jobId().equals(jobId))
                .orElseThrow(() -> new JobNotFoundException(jobId));
        String key = master ? d.masterKey() : d.previewKey();
        if (key == null || d.status() == com.loofie.core.draft.domain.DraftStatus.PURGED) {
            throw new TransitionGuardException(master ? "This video's file was removed" : "This video has no preview");
        }
        Instant now = clock.instant();
        java.net.URI url = uploads.viewUrl(key, DraftRules.MASTER_LINK_TTL);
        tx.executeWithoutResult(s -> audit.record(actor, AuditLog.DRAFT_OPENED_BY_OPS, d.creatorId(), f.brandOrgId(),
                why, Map.of("jobId", jobId.toString(), "draftId", draftId.toString(), "master", master)));
        return new DraftViews.Link(url, now.plus(DraftRules.MASTER_LINK_TTL));
    }
}
