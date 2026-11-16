package com.loofie.core.refund.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.RefundSupport;
import com.loofie.core.job.app.RefundSupport.Basis;
import com.loofie.core.job.app.TimerRefunds;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.TransitionTable;
import com.loofie.core.notification.app.NotificationService;
import com.loofie.core.order.app.OrderPricing;
import com.loofie.core.order.app.OrderPricing.Terms;
import com.loofie.core.refund.domain.ApprovalPolicy;
import com.loofie.core.refund.domain.ApprovalPolicy.Required;
import com.loofie.core.refund.domain.ApprovalPolicy.Step;
import com.loofie.core.refund.domain.RefundAmounts;
import com.loofie.core.refund.domain.RefundCase;
import com.loofie.core.refund.domain.RefundReason;
import com.loofie.core.refund.domain.RefundRuleException;
import com.loofie.core.refund.infra.RefundRequestRepository;
import com.loofie.core.refund.infra.RefundRequestRepository.Row;
import com.loofie.core.refund.infra.RefundRequestRepository.Status;

/**
 * Money going out (DECISIONS.md section 6): request, then approve; the money moves only after the last approval.
 * <ul>
 *   <li>A request works out the case and the amounts from the job's stage and the price the brand paid, and the
 *       approvals it needs, once. It holds the job, so nothing changes the case while it waits.</li>
 *   <li>The last approval runs the job's action (cancel / refund) in the same transaction: approved and moved, or
 *       neither.</li>
 *   <li>A decided request never changes (database trigger); a change is a new request.</li>
 * </ul>
 */
@Service
public class RefundService implements TimerRefunds {

    static final Duration HISTORY = Duration.ofDays(90);

    /** What ops and the approver see. Money in paise. */
    public record RefundView(UUID id, UUID jobId, UUID brandOrgId, JobAction action, RefundCase refundCase,
                             RefundReason reason, String note, long brandCashPaise, long brandCreditPaise,
                             long creatorKillFeePaise, long totalPaise, boolean creatorAccepted,
                             boolean leadMaySelfApprove, boolean founderRequired, UUID requestedBy,
                             String requestedByRole, Instant requestedAt, Status status, UUID leadApprovedBy,
                             UUID founderApprovedBy, UUID decidedBy, Instant decidedAt, String decisionNote,
                             String waitingFor) {
    }

    /** A request plus the brand's refund history, for the approver. */
    public record RefundDetail(RefundView request, List<RefundView> brandHistory, int approvedToBrandLast30Days) {
    }

    private final RefundRequestRepository repo;
    private final RefundSupport jobs;
    private final JobTransitionService engine;
    private final OrderPricing pricing;
    private final NotificationService notifications;
    private final Clock clock;

    public RefundService(RefundRequestRepository repo, RefundSupport jobs, JobTransitionService engine,
                         OrderPricing pricing, NotificationService notifications, Clock clock) {
        this.repo = repo;
        this.jobs = jobs;
        this.engine = engine;
        this.pricing = pricing;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Ops asks for a cancellation or refund. Any ops person may ask; the note is required (it stays in core). */
    @Transactional
    public RefundView request(Actor actor, UUID jobId, JobAction action, RefundReason reason, String note) {
        actor.requireRole(ActorRole.OPS, "Refunds are requested by ops");
        if (note == null || note.isBlank()) {
            throw new RefundRuleException("Say why (the note stays inside Loofie)");
        }
        if (reason == null) {
            throw new RefundRuleException("A refund needs a reason code");
        }
        return open(actor, jobId, action, reason, note.trim());
    }

    /** The timer: the replacement window passed with no creator. Loofie's own failure unless the brand shipped late. */
    @Override
    @Transactional
    public void replacementExpired(UUID jobId, Instant now) {
        Basis b = jobs.basis(jobId);
        RefundReason reason = "BRAND_LATE_SHIP".equals(b.lastReasonCode()) ? RefundReason.BRAND_LATE_SHIP
                : RefundReason.LOOFIE_NO_CREATOR;
        open(Actor.system(), jobId, JobAction.REPLACEMENT_EXPIRED, reason, "replacement window ended with no creator");
    }

    private RefundView open(Actor actor, UUID jobId, JobAction action, RefundReason reason, String note) {
        Instant now = clock.instant();
        Basis b = jobs.basis(jobId);
        if (TransitionTable.find(b.status(), action).isEmpty()) {
            throw new IllegalTransitionException(b.status(), action);
        }
        if (!JobTransitionService.isMoneyAction(b.status(), action)) {
            throw new RefundRuleException(action + " from " + b.status() + " moves no money: use the action directly");
        }
        RefundCase refundCase = RefundCase.of(action, b.status(), b.lastReasonCode());
        Terms terms = pricing.terms(b.orderId());
        RefundAmounts amounts = RefundAmounts.of(refundCase, b.creatorFeePaise(), terms.loofieFeeBp(), terms.gstBp());
        int recent = repo.approvedForBrandSince(b.brandOrgId(), now.minus(Duration.ofDays(ApprovalPolicy.WINDOW_DAYS)));
        Required required = ApprovalPolicy.required(amounts.total(), b.creatorEverAccepted(), recent);

        jobs.hold(jobId, b.version()); // refuses a job that moved meanwhile, or one with an open request
        Row row = new Row(Ids.newId(), jobId, b.brandOrgId(), action, refundCase, reason, note, amounts.brandCash(),
                amounts.brandCredit(), amounts.creatorKillFee(), b.creatorEverAccepted(), required.leadMaySelfApprove(),
                required.founderRequired(), actor.userId(), actor.role().name(), now, Status.REQUESTED, null, null,
                null, null, null, null, null);
        repo.insert(row);
        notifications.toOps("REFUND_REQUESTED", jobId, summary(row), now);
        if (required.founderRequired()) {
            notifications.toFounder("REFUND_NEEDS_FOUNDER", jobId, summary(row), now);
        }
        return view(row);
    }

    /**
     * An approval step. The last one runs the job's action in this transaction. Two approvers at the same moment
     * are serialised on the row lock; the second sees the first one's result.
     */
    @Transactional
    public RefundView approve(Actor actor, UUID requestId, String note) {
        Instant now = clock.instant();
        Row r = lockOpen(requestId);
        Step step = ApprovalPolicy.nextStep(new Required(r.leadMaySelfApprove(), r.founderRequired()), r.requestedBy(),
                r.leadApprovedBy(), actor.userId(), actor.role());
        if (step == Step.LEAD) {
            repo.leadApproved(r.id(), actor.userId(), now);
            if (r.founderRequired()) {
                notifications.toFounder("REFUND_NEEDS_FOUNDER", r.jobId(), summary(r), now);
                return view(repo.find(r.id()).orElseThrow());
            }
        } else {
            repo.founderApproved(r.id(), actor.userId(), now);
        }
        // Last approval: the money action runs now, as part of this decision.
        Actor runAs = r.action() == JobAction.REPLACEMENT_EXPIRED ? Actor.system() : actor;
        String reason = runAs == actor ? "refund request " + r.id() + " approved: " + r.note() : null;
        engine.transition(TransitionCommand.of(r.jobId(), r.action(), runAs, reason).forApprovedRefund(r.id()));
        repo.decide(r.id(), Status.APPROVED, actor.userId(), now, blankToNull(note));
        Row decided = repo.find(r.id()).orElseThrow();
        Map<String, Object> toBrand = new LinkedHashMap<>();
        toBrand.put("refundCase", decided.refundCase().name());
        toBrand.put("brandCashPaise", decided.brandCash());
        toBrand.put("brandCreditPaise", decided.brandCredit());
        notifications.toBrand(decided.brandOrgId(), "REFUND_APPROVED", decided.jobId(), toBrand, now);
        notifications.toOps("REFUND_APPROVED", decided.jobId(), summary(decided), now);
        return view(decided);
    }

    /** An OPS_LEAD or the founder says no. The job is released and can move again. */
    @Transactional
    public RefundView reject(Actor actor, UUID requestId, String note) {
        if (actor.role() != ActorRole.OPS_LEAD && actor.role() != ActorRole.FOUNDER) {
            throw new RefundRuleException("Only an OPS_LEAD or the founder may reject a refund");
        }
        if (note == null || note.isBlank()) {
            throw new RefundRuleException("Say why it is rejected");
        }
        return close(lockOpen(requestId), Status.REJECTED, actor, note.trim());
    }

    /** The person who asked takes it back (or a lead does). */
    @Transactional
    public RefundView withdraw(Actor actor, UUID requestId) {
        Row r = lockOpen(requestId);
        boolean own = actor.userId() != null && actor.userId().equals(r.requestedBy());
        if (!own && actor.role() != ActorRole.OPS_LEAD && actor.role() != ActorRole.FOUNDER) {
            throw new RefundRuleException("Only the person who asked, an OPS_LEAD or the founder may withdraw it");
        }
        return close(r, Status.WITHDRAWN, actor, null);
    }

    @Transactional(readOnly = true)
    public List<RefundView> open(Actor actor, int limit) {
        actor.requireRole(ActorRole.OPS, "Refunds are for ops");
        return repo.open(Math.min(Math.max(limit, 1), 200)).stream().map(RefundService::view).toList();
    }

    @Transactional(readOnly = true)
    public RefundDetail detail(Actor actor, UUID requestId) {
        actor.requireRole(ActorRole.OPS, "Refunds are for ops");
        Row r = repo.find(requestId).orElseThrow(() -> new JobNotFoundException(requestId));
        Instant now = clock.instant();
        List<RefundView> history = repo.brandHistory(r.brandOrgId(), now.minus(HISTORY)).stream()
                .filter(h -> !h.id().equals(r.id())).map(RefundService::view).toList();
        return new RefundDetail(view(r), history,
                repo.approvedForBrandSince(r.brandOrgId(), now.minus(Duration.ofDays(ApprovalPolicy.WINDOW_DAYS))));
    }

    private RefundView close(Row r, Status status, Actor actor, String note) {
        Instant now = clock.instant();
        repo.decide(r.id(), status, actor.userId(), now, note);
        jobs.release(r.jobId());
        Row decided = repo.find(r.id()).orElseThrow();
        notifications.toOps("REFUND_" + status.name(), r.jobId(), summary(decided), now);
        return view(decided);
    }

    private Row lockOpen(UUID requestId) {
        Row r = repo.lock(requestId).orElseThrow(() -> new JobNotFoundException(requestId));
        if (r.status() != Status.REQUESTED) {
            throw new RefundRuleException("This refund request is already " + r.status());
        }
        return r;
    }

    /** For inboxes: codes and amounts, never the ops note. */
    private static Map<String, Object> summary(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refundRequestId", r.id().toString());
        m.put("refundCase", r.refundCase().name());
        m.put("reason", r.reason().name());
        m.put("totalPaise", r.total());
        m.put("founderRequired", r.founderRequired());
        return m;
    }

    static RefundView view(Row r) {
        String waiting = r.status() != Status.REQUESTED ? null
                : r.leadApprovedBy() == null ? (r.leadMaySelfApprove() ? "OPS_LEAD (may be the requester)"
                        : "OPS_LEAD (not the requester)")
                : "FOUNDER";
        return new RefundView(r.id(), r.jobId(), r.brandOrgId(), r.action(), r.refundCase(), r.reason(), r.note(),
                r.brandCash(), r.brandCredit(), r.creatorKillFee(), r.total(), r.creatorAccepted(),
                r.leadMaySelfApprove(), r.founderRequired(), r.requestedBy(), r.requestedByRole(), r.requestedAt(),
                r.status(), r.leadApprovedBy(), r.founderApprovedBy(), r.decidedBy(), r.decidedAt(), r.decisionNote(),
                waiting);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
