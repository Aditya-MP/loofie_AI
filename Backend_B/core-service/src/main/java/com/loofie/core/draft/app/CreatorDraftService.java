package com.loofie.core.draft.app;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.draft.domain.Brief;
import com.loofie.core.draft.domain.VideoChecks;
import com.loofie.core.draft.infra.BriefRepository;
import com.loofie.core.draft.infra.DraftRepository;
import com.loofie.core.draft.infra.ReviewRepository;
import com.loofie.core.job.app.JobFacts;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.storage.Uploads;

/**
 * The creator's side of F7: get an upload link, upload the video straight to storage, then submit its key. The video
 * is checked and previewed in the background ({@link DraftProcessor}); the brand sees it only once both passed.
 */
@Service
public class CreatorDraftService {

    public record UploadTicket(Uploads.UploadTicket ticket, DraftViews.Limits limits) {
    }

    private final JobFacts jobs;
    private final Uploads uploads;
    private final DraftRepository drafts;
    private final BriefRepository briefs;
    private final ReviewRepository reviews;
    private final DraftMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DraftReadFence fence;

    CreatorDraftService(DraftReadFence fence, JobFacts jobs, Uploads uploads, DraftRepository drafts, BriefRepository briefs,
                        ReviewRepository reviews, DraftMapper mapper, TransactionTemplate tx, Clock clock) {
        this.fence = fence;
        this.jobs = jobs;
        this.uploads = uploads;
        this.drafts = drafts;
        this.briefs = briefs;
        this.reviews = reviews;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    public UploadTicket uploadTicket(Actor actor, UUID jobId, String contentType, Long sizeBytes) {
        actor.requireRole(ActorRole.CREATOR, "Only the creator on the job uploads its video");
        JobFacts.Facts f = jobs.forActor(actor, jobId);
        requireUploadable(f);
        return new UploadTicket(uploads.issue(actor.userId(), Uploads.Purpose.DRAFT_VIDEO, contentType, sizeBytes),
                limits(brief(jobId)));
    }

    /**
     * Attaches an uploaded video to the job and queues it for checking. The store is asked first (outside any
     * transaction): the key must be hers, for a video, and the file really there within 500 MB.
     */
    public DraftViews.Draft submit(Actor actor, UUID jobId, String key) {
        actor.requireRole(ActorRole.CREATOR, "Only the creator on the job uploads its video");
        requireUploadable(jobs.forActor(actor, jobId));
        if (key == null || key.isBlank()) {
            throw new Uploads.UploadRefusedException("Say which upload this is (key)");
        }
        long size = uploads.verify(key.trim(), actor.userId(), Uploads.Purpose.DRAFT_VIDEO);
        UUID id = Ids.newId();
        try {
            tx.executeWithoutResult(s -> {
                JobFacts.Facts f = jobs.forActor(actor, jobId); // again, in the transaction: the job may have moved
                requireUploadable(f);
                int attempt = drafts.nextAttempt(jobId);
                if (attempt > DraftRules.MAX_UPLOADS_PER_JOB) {
                    throw new TransitionGuardException("This job already has " + DraftRules.MAX_UPLOADS_PER_JOB
                            + " uploads; contact support");
                }
                String kind = f.status() == JobStatus.REVISION_REQUESTED ? "REVISION" : "FIRST";
                drafts.insert(id, jobId, actor.userId(), attempt, kind, key.trim(), size, clock.instant());
                if (!uploads.markUsed(key.trim(), size)) {
                    throw new Uploads.UploadRefusedException("This upload is already attached.");
                }
            });
        } catch (DuplicateKeyException e) {
            throw new TransitionGuardException("A video for this job is already being checked. Wait for the result.");
        }
        return mapper.draft(drafts.find(id).orElseThrow());
    }

    public DraftViews.CreatorDrafts view(Actor actor, UUID jobId) {
        actor.requireRole(ActorRole.CREATOR, "Only the creator on the job sees its uploads");
        return fence.read(() -> viewInTx(actor, jobId));
    }

    private DraftViews.CreatorDrafts viewInTx(Actor actor, UUID jobId) {
        JobFacts.Facts f = jobs.forActor(actor, jobId);
        Brief b = brief(jobId);
        // Only her own uploads: after a replacement, the earlier creator's videos are not hers to see.
        List<DraftRepository.Draft> mine = drafts.forJob(jobId).stream()
                .filter(d -> d.creatorId().equals(actor.userId())).toList();
        boolean inFlight = mine.stream().anyMatch(d -> d.status().inFlight());
        String note = !DraftRules.UPLOAD_STATES.contains(f.status()) ? notUploadable(f.status())
                : inFlight ? "Your video is being checked. You can upload again if it fails." : null;
        DraftViews.Review revision = f.status() == JobStatus.REVISION_REQUESTED ? reviews.forJob(jobId).stream()
                .filter(r -> r.decision().equals("REVISION")).findFirst().map(mapper::review).orElse(null) : null;
        return new DraftViews.CreatorDrafts(jobId, f.status(), note == null, note, limits(b), DraftMapper.brief(b),
                revision, mine.stream().map(mapper::draft).toList());
    }

    Brief brief(UUID jobId) {
        return briefs.find(jobId).map(BriefRepository.Stored::brief).orElseGet(Brief::defaults);
    }

    static DraftViews.Limits limits(Brief b) {
        return new DraftViews.Limits(Uploads.Purpose.DRAFT_VIDEO.maxBytes(), Uploads.Purpose.DRAFT_VIDEO.types(),
                b.minSeconds(), b.maxSeconds(), VideoChecks.MIN_WIDTH, VideoChecks.MIN_HEIGHT);
    }

    private static void requireUploadable(JobFacts.Facts f) {
        if (!DraftRules.UPLOAD_STATES.contains(f.status())) {
            throw new TransitionGuardException(notUploadable(f.status()));
        }
    }

    private static String notUploadable(JobStatus s) {
        return switch (s) {
            case DRAFT_SUBMITTED, IN_REVIEW -> "The brand is reviewing your video.";
            case APPROVED, PAYOUT_PENDING, PAID_OUT, CLOSED -> "The video is approved.";
            case DELIVERY_ISSUE -> "Production is paused while the delivery issue is sorted out.";
            default -> "You can upload the video once production has started.";
        };
    }

}
