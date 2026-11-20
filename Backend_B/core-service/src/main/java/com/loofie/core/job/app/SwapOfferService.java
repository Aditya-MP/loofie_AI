package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.common.ScreenReadFence;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.CreatorRoster;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.job.infra.SwapOfferRepository;
import com.loofie.core.job.infra.SwapOfferRepository.Offer;
import com.loofie.core.notification.app.NotificationService;

/**
 * F9 swaps (Foundation v2: "ops offers the next creator from the shortlist at the same or a lower rate. The brand
 * approves the swap."). Ops offers one creator at a time; the brand approves (the engine then invites that creator)
 * or declines with a reason, and ops offers another. The 10-day refund window keeps running throughout.
 */
@Service
public class SwapOfferService {

    /** What the brand sees: the offer and the creator's public card (null when she is no longer bookable). */
    public record BrandOffer(UUID offerId, Instant offeredAt, Instant refundIfNoCreatorBy, CreatorRoster.Card creator) {
    }

    /** An offer as ops sees it (the brand never sees the ops note or the suggestion id). */
    public record OfferView(UUID id, UUID jobId, UUID creatorId, int ratePerReelRupees, String status, String note,
                            String suggestionId, UUID offeredBy, Instant offeredAt, UUID decidedBy, Instant decidedAt,
                            String declineReason, String closedReason) {
        static OfferView of(Offer o) {
            return new OfferView(o.id(), o.jobId(), o.creatorId(), o.ratePerReelRupees(), o.status().name(), o.note(),
                    o.suggestionId(), o.offeredBy(), o.offeredAt(), o.decidedBy(), o.decidedAt(), o.declineReason(),
                    o.closedReason());
        }
    }

    private final JobRepository jobs;
    private final AssignmentRepository assignments;
    private final SwapOfferRepository offers;
    private final CreatorRoster roster;
    private final JobFacts facts;
    private final JobTransitionService engine;
    private final NotificationService notifications;
    private final Clock clock;
    private final ScreenReadFence fence;

    public SwapOfferService(JobRepository jobs, AssignmentRepository assignments, SwapOfferRepository offers,
                            CreatorRoster roster, JobFacts facts, JobTransitionService engine,
                            NotificationService notifications, Clock clock, ScreenReadFence fence) {
        this.fence = fence;
        this.jobs = jobs;
        this.assignments = assignments;
        this.offers = offers;
        this.roster = roster;
        this.facts = facts;
        this.engine = engine;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public OfferView offer(Actor actor, UUID jobId, UUID creatorId, String note, String suggestionId) {
        actor.requireRole(ActorRole.OPS, "Ops offers replacement creators");
        String why = note == null ? "" : note.strip();
        if (why.length() < 5) {
            throw new TransitionGuardException("Say why this creator (note, at least 5 characters)");
        }
        if (creatorId == null) {
            throw new TransitionGuardException("Say which creator (creatorId)");
        }
        Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        if (!JobTransitionService.WAITING_FOR_CREATOR.contains(job.getStatus())) {
            throw new TransitionGuardException("A creator can be offered only while the job has none (DECLINED or "
                    + "REPLACEMENT_NEEDED); it is " + job.getStatus());
        }
        if (job.isRefundHold()) {
            throw new TransitionGuardException("A refund request is open for this job; decide it first");
        }
        boolean wasOnJob = assignments.findByJobIdOrderByOpenedAt(jobId).stream()
                .map(Assignment::getCreatorId).anyMatch(creatorId::equals);
        if (wasOnJob) {
            throw new TransitionGuardException("This creator was already on this job; offer someone else");
        }
        CreatorRoster.Card card = roster.bookableCard(creatorId).orElseThrow(() -> new TransitionGuardException(
                "This creator cannot be booked (profile incomplete, paused, blocked or account not active)"));
        if (card.ratePerReelRupees() == null) {
            throw new TransitionGuardException("This creator has no rate on her profile");
        }
        if (card.ratePerReelRupees() * 100L > job.getCreatorFeePaise()) {
            throw new TransitionGuardException("This creator's rate (Rs " + card.ratePerReelRupees() + ") is above the "
                    + "job's creator fee (Rs " + job.getCreatorFeePaise() / 100 + "): offer the same or a lower rate");
        }
        Instant now = clock.instant();
        String suggestion = suggestionId == null || suggestionId.isBlank() ? null : suggestionId.strip();
        if (suggestion != null && suggestion.length() > 100) {
            throw new TransitionGuardException("suggestionId is at most 100 characters");
        }
        Offer o = new Offer(Ids.newId(), jobId, creatorId, card.ratePerReelRupees(), SwapOfferRepository.Status.OFFERED,
                why.length() > 500 ? why.substring(0, 500) : why, suggestion, actor.userId(), now, null, null, null,
                null);
        if (!offers.insert(o)) {
            throw new TransitionGuardException("This job already has an open offer; withdraw it first");
        }
        notifications.toBrand(job.getBrandOrgId(), "SWAP_OFFERED", jobId, Map.of("offerId", o.id().toString()), now);
        return OfferView.of(o);
    }

    @Transactional
    public void withdraw(Actor actor, UUID jobId, String reason) {
        actor.requireRole(ActorRole.OPS, "Ops withdraws its offers");
        if (reason == null || reason.strip().length() < 5) {
            throw new TransitionGuardException("Say why (reason, at least 5 characters)");
        }
        if (!offers.withdrawOpen(jobId, actor.userId(), "OPS", clock.instant())) {
            throw new JobNotFoundException(jobId); // no open offer
        }
    }

    /** Behind the shared screen fence: one read-only transaction, one connection, a bounded queue. */
    public BrandOffer brandView(Actor actor, UUID jobId) {
        requireBrand(actor);
        return fence.read("swap", () -> {
            facts.forActor(actor, jobId); // scope: someone else's job is "not found"
            Offer o = offers.open(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
            Job job = jobs.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
            return new BrandOffer(o.id(), o.offeredAt(), job.getReplacementDueAt(),
                    roster.bookableCard(o.creatorId()).orElse(null));
        });
    }

    /** The brand admin approves: the engine invites the offered creator (and closes the offer) in one transaction. */
    @Transactional // the checks and the engine share one transaction and one connection
    public TransitionResult approve(Actor actor, UUID jobId) {
        if (actor.role().acting() != ActorRole.BRAND_ADMIN) {
            throw new com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException(
                    "Only the brand admin approves a creator swap");
        }
        facts.forActor(actor, jobId);
        Offer o = offers.open(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        if (roster.bookableCard(o.creatorId()).isEmpty()) {
            throw new TransitionGuardException("This creator is no longer available; ops will offer another");
        }
        return engine.transition(TransitionCommand.of(jobId, JobAction.APPROVE_SWAP, actor, null)
                .withCreator(o.creatorId()));
    }

    @Transactional
    public void decline(Actor actor, UUID jobId, String reason) {
        if (actor.role().acting() != ActorRole.BRAND_ADMIN) {
            throw new com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException(
                    "Only the brand admin decides a creator swap");
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5) {
            throw new TransitionGuardException("Say why (reason, at least 5 characters), so ops can offer better");
        }
        facts.forActor(actor, jobId);
        Offer o = offers.open(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        Instant now = clock.instant();
        if (!offers.decline(o.id(), actor.userId(), why.length() > 500 ? why.substring(0, 500) : why, now)) {
            throw new JobNotFoundException(jobId); // decided meanwhile
        }
        notifications.toOps("SWAP_DECLINED", jobId, Map.of("offerId", o.id().toString()), now);
    }

    public List<OfferView> forOps(Actor actor, UUID jobId) {
        actor.requireRole(ActorRole.OPS, "Ops only");
        return fence.read("swap", () -> {
            facts.forActor(actor, jobId);
            return offers.forJob(jobId).stream().map(OfferView::of).toList();
        });
    }

    private static void requireBrand(Actor actor) {
        ActorRole r = actor.role().acting();
        if (r != ActorRole.BRAND_ADMIN && r != ActorRole.BRAND_MEMBER) {
            throw new com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException("Brands only");
        }
    }
}
