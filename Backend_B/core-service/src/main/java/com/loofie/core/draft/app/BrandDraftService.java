package com.loofie.core.draft.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.draft.domain.Brief;
import com.loofie.core.draft.infra.DraftRepository;
import com.loofie.core.draft.infra.ReviewRepository;
import com.loofie.core.identity.app.AuditLog;
import com.loofie.core.job.app.JobFacts;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.messaging.EventEnvelope;
import com.loofie.core.messaging.Outbox;
import com.loofie.core.notification.app.NotificationService;
import com.loofie.core.storage.Uploads;

/**
 * The brand's side of F7: watch the watermarked preview, answer the brief's checklist, then approve, ask for the one
 * free revision (for the items answered "no" only) or reject (after the free revision; it becomes a dispute, F9).
 * The full-quality video is released only after approval, by a short-lived audited link. Then a 1-5 rating (D7).
 */
@Service
public class BrandDraftService {

    public enum Decision { APPROVE, REVISION, REJECT }

    public record AnswerInput(String key, Boolean ok, String note) {
    }

    public record ReviewInput(UUID draftId, Decision decision, List<AnswerInput> answers, String note,
                              Long expectedVersion) {
    }

    static final String RATING_EVENT = "JobRating";

    private final JobFacts jobs;
    private final JobTransitionService engine;
    private final DraftRepository drafts;
    private final ReviewRepository reviews;
    private final CreatorDraftService creatorSide;
    private final Uploads uploads;
    private final NotificationService notifications;
    private final AuditLog audit;
    private final Outbox outbox;
    private final DraftMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final com.loofie.core.common.ScreenReadFence fence;

    BrandDraftService(com.loofie.core.common.ScreenReadFence fence, JobFacts jobs, JobTransitionService engine, DraftRepository drafts, ReviewRepository reviews,
                      CreatorDraftService creatorSide, Uploads uploads, NotificationService notifications,
                      AuditLog audit, Outbox outbox, DraftMapper mapper, TransactionTemplate tx, Clock clock) {
        this.fence = fence;
        this.jobs = jobs;
        this.engine = engine;
        this.drafts = drafts;
        this.reviews = reviews;
        this.creatorSide = creatorSide;
        this.uploads = uploads;
        this.notifications = notifications;
        this.audit = audit;
        this.outbox = outbox;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    public DraftViews.BrandDraft view(Actor actor, UUID jobId) {
        requireBrand(actor);
        return fence.read("draft", () -> viewInTx(actor, jobId));
    }

    private DraftViews.BrandDraft viewInTx(Actor actor, UUID jobId) {
        JobFacts.Facts f = jobs.forActor(actor, jobId);
        Brief brief = creatorSide.brief(jobId);
        DraftRepository.Draft current = drafts.current(jobId).orElse(null);
        Instant now = clock.instant();
        java.net.URI preview = current == null ? null
                : uploads.viewUrl(current.previewKey(), DraftRules.PREVIEW_LINK_TTL);
        Instant approvedAt = drafts.approvedAt(jobId).orElse(null);
        Integer stars = reviews.rating(jobId).map(ReviewRepository.Rating::stars).orElse(null);
        Instant ratingOpenUntil = approvedAt == null || stars != null ? null : approvedAt.plus(DraftRules.RATING_WINDOW);
        return new DraftViews.BrandDraft(jobId, f.status(), f.version(), current == null ? null : mapper.draft(current),
                preview, preview == null ? null : now.plus(DraftRules.PREVIEW_LINK_TTL), DraftMapper.brief(brief),
                f.freeRevisionUsed(), f.reviewDueAt(), approvedAt != null && DraftRules.APPROVED_STATES.contains(f.status()),
                reviews.forJob(jobId).stream().map(mapper::review).toList(), stars,
                ratingOpenUntil != null && now.isBefore(ratingOpenUntil) ? ratingOpenUntil : null);
    }

    /** One decision on the video the brand is looking at, with the whole checklist answered. */
    public DraftViews.BrandDraft review(Actor actor, UUID jobId, ReviewInput in) {
        requireBrand(actor);
        if (in == null || in.decision() == null || in.draftId() == null) {
            throw new TransitionGuardException("Say which video (draftId) and the decision: APPROVE, REVISION or REJECT");
        }
        JobFacts.Facts f = jobs.forActor(actor, jobId);
        if (in.decision() != Decision.REVISION && actor.role() != com.loofie.core.job.domain.ActorRole.BRAND_ADMIN) {
            throw new ActorNotAllowedException("Only your company's admin approves or rejects (it releases payment); "
                    + "you can ask for the free revision");
        }
        if (f.status() != JobStatus.IN_REVIEW) {
            throw new TransitionGuardException("This job is not waiting for your review (it is " + f.status() + ")");
        }
        Brief brief = creatorSide.brief(jobId);
        List<DraftViews.Answer> answers = answers(brief, in);
        String note = trim(in.note(), 1000);
        if (in.decision() == Decision.REJECT && (note == null || note.length() < 10)) {
            throw new TransitionGuardException("Say why the video is rejected (at least 10 characters); ops reviews it");
        }
        tx.executeWithoutResult(s -> {
            DraftRepository.Draft current = drafts.currentForUpdate(jobId).orElseThrow(
                    () -> new TransitionGuardException("There is no video to review yet"));
            if (!current.id().equals(in.draftId())) {
                throw new TransitionGuardException("A newer video arrived; reload and review that one");
            }
            JobAction action = switch (in.decision()) {
                case APPROVE -> JobAction.APPROVE;
                case REVISION -> JobAction.REQUEST_REVISION;
                case REJECT -> JobAction.REJECT;
            };
            engine.transition(new TransitionCommand(jobId, action, actor, null, null, null, null,
                    in.expectedVersion()));
            Instant now = clock.instant();
            try {
                reviews.insert(Ids.newId(), jobId, current.id(), in.decision().name(), mapper.answersJson(answers), note,
                        actor.userId(), actor.role().name(), now);
            } catch (DuplicateKeyException e) {
                throw new TransitionGuardException("This video was already reviewed");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("draftId", current.id().toString());
            switch (in.decision()) {
                case APPROVE -> notifications.toCreator(current.creatorId(), "DRAFT_APPROVED", jobId, payload, now);
                case REVISION -> {
                    payload.put("items", answers.stream().filter(a -> !a.ok()).map(DraftViews.Answer::key).toList());
                    notifications.toCreator(current.creatorId(), "DRAFT_REVISION_REQUESTED", jobId, payload, now);
                }
                case REJECT -> {
                    notifications.toCreator(current.creatorId(), "DRAFT_REJECTED", jobId, payload, now);
                    notifications.toOps("DRAFT_REJECTED", jobId, payload, now); // a dispute to review (F9)
                }
            }
        });
        return view(actor, jobId);
    }

    /** The full-quality video, after approval only: a 15-minute link, recorded in the audit log. */
    public DraftViews.Link master(Actor actor, UUID jobId) {
        requireBrand(actor);
        JobFacts.Facts f = jobs.forActor(actor, jobId);
        DraftRepository.Draft approved = drafts.current(jobId).filter(d -> d.approvedAt() != null).orElse(null);
        if (!DraftRules.APPROVED_STATES.contains(f.status()) || approved == null) {
            // Same answer as "no such video": before approval the master does not exist for the brand.
            throw new com.loofie.core.job.domain.JobExceptions.JobNotFoundException(jobId);
        }
        Instant now = clock.instant();
        java.net.URI url = uploads.viewUrl(approved.masterKey(), DraftRules.MASTER_LINK_TTL);
        tx.executeWithoutResult(s -> audit.record(actor, AuditLog.DRAFT_MASTER_DOWNLOADED, approved.creatorId(),
                f.brandOrgId(), null, Map.of("jobId", jobId.toString(), "draftId", approved.id().toString())));
        return new DraftViews.Link(url, now.plus(DraftRules.MASTER_LINK_TTL));
    }

    /** D7: 1-5 stars and an optional comment, once per job, within 7 days of approval. Stars go to Backend A. */
    public DraftViews.BrandDraft rate(Actor actor, UUID jobId, Integer stars, String comment) {
        requireBrand(actor);
        JobFacts.Facts f = jobs.forActor(actor, jobId);
        if (stars == null || stars < 1 || stars > 5) {
            throw new TransitionGuardException("Rate 1 to 5 stars");
        }
        String text = trim(comment, 1000);
        Instant now = clock.instant();
        DraftRepository.Draft approved = drafts.current(jobId).filter(d -> d.approvedAt() != null).orElse(null);
        if (!DraftRules.APPROVED_STATES.contains(f.status()) || approved == null) {
            throw new TransitionGuardException("You can rate the creator once the video is approved");
        }
        if (now.isAfter(approved.approvedAt().plus(DraftRules.RATING_WINDOW))) {
            throw new TransitionGuardException("Ratings are open for 7 days after approval");
        }
        try {
            tx.executeWithoutResult(s -> {
                reviews.insertRating(jobId, approved.creatorId(), stars, text, actor.userId(), now);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("jobId", jobId.toString());
                payload.put("creatorId", approved.creatorId().toString());
                payload.put("stars", stars); // the comment stays here: free text never leaves core
                outbox.enqueue(Outbox.JOB_STREAM, jobId.toString(), new EventEnvelope(Ids.newId(), RATING_EVENT, 1,
                        now, EventEnvelope.PRODUCER, jobId, f.version(), Ids.newId(), null, payload));
            });
        } catch (DuplicateKeyException e) {
            throw new TransitionGuardException("This job is already rated");
        }
        return view(actor, jobId);
    }

    /** Every checklist item answered once; a revision is only for "no" items, and each needs a note. */
    private static List<DraftViews.Answer> answers(Brief brief, ReviewInput in) {
        Map<String, AnswerInput> given = new HashMap<>();
        for (AnswerInput a : in.answers() == null ? List.<AnswerInput>of() : in.answers()) {
            if (a == null || a.key() == null || !brief.hasItem(a.key())) {
                throw new TransitionGuardException("Unknown checklist item " + (a == null ? null : a.key()));
            }
            if (a.ok() == null) {
                throw new TransitionGuardException("Answer yes or no for " + a.key());
            }
            if (given.put(a.key(), a) != null) {
                throw new TransitionGuardException("Checklist item answered twice: " + a.key());
            }
        }
        List<DraftViews.Answer> out = new ArrayList<>();
        for (Brief.ChecklistItem item : brief.checklist()) {
            AnswerInput a = given.get(item.key());
            if (a == null) {
                throw new TransitionGuardException("Answer every checklist item; missing: " + item.key());
            }
            String note = trim(a.note(), DraftRules.MAX_NOTE);
            if (in.decision() == Decision.REVISION && !a.ok() && (note == null || note.length() < 5)) {
                throw new TransitionGuardException("Say what to change for \"" + item.label() + "\" (at least 5 characters)");
            }
            out.add(new DraftViews.Answer(item.key(), item.label(), a.ok(), note));
        }
        if (in.decision() == Decision.REVISION && out.stream().allMatch(DraftViews.Answer::ok)) {
            throw new TransitionGuardException("A free revision is for checklist items the video misses: answer "
                    + "\"no\" to at least one, or approve");
        }
        return out;
    }

    private static void requireBrand(Actor actor) {
        if (!actor.role().isBrand()) {
            throw new ActorNotAllowedException("Only the brand reviews the video");
        }
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        if (t.length() > max) {
            throw new TransitionGuardException("Text is too long (at most " + max + " characters)");
        }
        return t;
    }
}
