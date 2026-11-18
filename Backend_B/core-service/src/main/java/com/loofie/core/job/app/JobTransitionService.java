package com.loofie.core.job.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;

import com.loofie.core.common.Ids;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.CreatorEligibility;
import com.loofie.core.job.domain.Courier;
import com.loofie.core.job.domain.DeliverySource;
import com.loofie.core.job.domain.DraftGate;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.StaleJobVersionException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.ReasonCode;
import com.loofie.core.job.domain.Shipment;
import com.loofie.core.job.domain.TransitionRule;
import com.loofie.core.job.domain.TransitionTable;
import com.loofie.core.job.domain.WorkingCalendar;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.CourierRepository;
import com.loofie.core.job.infra.TrackingNumberRepository;
import com.loofie.core.job.infra.JobReadRepository;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.job.infra.ShipmentRepository;

/**
 * The single entry point for changing a job's state. Nothing else writes job status.
 *
 * <p>One transaction per call: optimistic-lock check (@Version), state update, assignment change,
 * deadline recalculation, job_events row and outbox row. Any failure rolls all of it back.
 * A race (e.g. auto-approve vs the brand's click) is settled by the optimistic lock: the loser
 * gets an {@code ObjectOptimisticLockingFailureException} and nothing is written for it.
 */
@Service
public class JobTransitionService {

    private final JobRepository jobs;
    private final AssignmentRepository assignments;
    private final ShipmentRepository shipments;
    private final JobEventRecorder recorder;
    private final WorkingCalendar calendar;
    private final Clock clock;
    private final MeterRegistry meters;
    private final CourierRepository couriers;
    private final TrackingNumberRepository trackingNumbers;
    private final JobReadRepository reads;
    private final DeliveryNotices deliveryNotices;
    private final CreatorEligibility eligibility;
    private final DraftGate drafts;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JobTransitionService.class);
    /** {@code loofie.deadlines.review-working-days}: empty = 120 calendar hours (today); 5 = DECISIONS.md, once signed off. */
    private Integer reviewWorkingDays;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setReviewWorkingDays(@org.springframework.beans.factory.annotation.Value("${loofie.deadlines.review-working-days:#{null}}") Integer days) {
        if (days != null && (days < 1 || days > 20)) {
            throw new IllegalArgumentException("loofie.deadlines.review-working-days must be 1-20 or empty");
        }
        this.reviewWorkingDays = days;
    }

    public JobTransitionService(JobRepository jobs, AssignmentRepository assignments, ShipmentRepository shipments,
                                JobEventRecorder recorder, WorkingCalendar calendar, Clock clock, MeterRegistry meters,
                                CourierRepository couriers, TrackingNumberRepository trackingNumbers,
                                JobReadRepository reads, DeliveryNotices deliveryNotices,
                                CreatorEligibility eligibility, DraftGate drafts) {
        this.eligibility = eligibility;
        this.drafts = drafts;
        this.reads = reads;
        this.deliveryNotices = deliveryNotices;
        this.couriers = couriers;
        this.trackingNumbers = trackingNumbers;
        this.jobs = jobs;
        this.assignments = assignments;
        this.shipments = shipments;
        this.recorder = recorder;
        this.calendar = calendar;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public TransitionResult transition(UUID jobId, JobAction action, Actor actor, String reason) {
        return transition(TransitionCommand.of(jobId, action, actor, reason));
    }

    @Transactional
    public TransitionResult transition(TransitionCommand cmd) {
        Job job = jobs.findById(cmd.jobId()).orElseThrow(() -> new JobNotFoundException(cmd.jobId()));
        // Scope first: someone outside the job learns nothing about it (not its state, version or rules).
        requireInScope(job, cmd.actor());
        if (cmd.expectedVersion() != null && !cmd.expectedVersion().equals(job.getVersion())) {
            throw new StaleJobVersionException(job.getId(), cmd.expectedVersion(), job.getVersion());
        }
        JobStatus start = job.getStatus();
        UUID correlationId = cmd.correlationId() != null ? cmd.correlationId() : Ids.newId();

        List<UUID> eventIds = new ArrayList<>();
        TransitionRule rule = requireRule(job.getStatus(), cmd.action());
        authorize(job, rule, cmd.actor(), cmd.reason(), cmd.details());
        requireRefundApproval(job, rule, cmd);
        if (cmd.refundRequestId() != null) {
            job.releaseRefundHold();
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        UUID eventId = applyStep(job, rule, cmd.actor(), cmd.reason(), cmd.creatorId(), cmd.details(), correlationId,
                null, facts);
        eventIds.add(eventId);

        // Automatic follow-ups (e.g. PRODUCT_RECEIVED -> IN_PRODUCTION) run as SYSTEM in the same transaction.
        for (JobAction next = followUpFor(job, rule); next != null; next = followUpFor(job, rule)) {
            rule = requireRule(job.getStatus(), next);
            eventId = applyStep(job, rule, Actor.system(), null, null, TransitionDetails.NONE, correlationId, eventId,
                    new LinkedHashMap<>());
            eventIds.add(eventId);
        }
        // F6: whoever must act next is told, after the follow-ups so the new clocks are in the notice.
        deliveryNotices.after(job, cmd.action(), start, facts, clock.instant());
        return new TransitionResult(job.getId(), start, job.getStatus(), job.getVersion(), eventIds);
    }

    /**
     * The table's fixed follow-up, plus follow-ups that depend on the job itself.
     * F6: a digital-only job has no product to ship, so accepting it starts production straight away.
     */
    private static JobAction followUpFor(Job job, TransitionRule rule) {
        if (rule.followUp() != null) {
            return rule.followUp();
        }
        if (rule.action() == JobAction.ACCEPT && job.isDigitalOnly()) {
            return JobAction.START_DIGITAL;
        }
        return null;
    }

    private static TransitionRule requireRule(JobStatus from, JobAction action) {
        return TransitionTable.find(from, action).orElseThrow(() -> new IllegalTransitionException(from, action));
    }

    /**
     * Money moves only after approval (DECISIONS.md section 6): a cancellation or refund runs only from an approved
     * refund request, and while a request is open the job is held so nothing changes the case it was approved for.
     * Cancelling an unpaid order moves no money and needs no request.
     */
    private static void requireRefundApproval(Job job, TransitionRule rule, TransitionCommand cmd) {
        if (cmd.refundRequestId() != null) {
            return;
        }
        if (isMoneyAction(job.getStatus(), rule.action())) {
            throw new TransitionGuardException(rule.action() + " moves money: create a refund request "
                    + "(POST /api/core/ops/jobs/" + job.getId() + "/refund-requests); it runs once approved");
        }
        if (job.isRefundHold()) {
            throw new TransitionGuardException("A refund request is open for this job; approve, reject or withdraw it "
                    + "first");
        }
    }

    public static boolean isMoneyAction(JobStatus from, JobAction action) {
        return switch (action) {
            case CANCEL -> from != JobStatus.AWAITING_PAYMENT;
            case RESOLVE_REFUND, REPLACEMENT_EXPIRED -> true;
            default -> false;
        };
    }

    private void authorize(Job job, TransitionRule rule, Actor actor, String reason, TransitionDetails details) {
        if (!rule.roles().contains(actor.role().acting())) {
            throw new ActorNotAllowedException(actor.role() + " may not " + rule.action() + " (allowed: " + rule.roles() + ")");
        }
        if (actor.role().isOps() && (reason == null || reason.isBlank())) {
            throw new TransitionGuardException("Ops actions need a reason");
        }
        // Reason codes go to the AI log and can count against a creator, so only ops may choose one.
        if (details.reasonCode() != null && !actor.role().isOps()) {
            throw new ActorNotAllowedException("Only ops may set a reason code");
        }
    }

    /**
     * A brand sees only its own jobs, a creator only the job they are currently assigned to. Anyone else
     * gets exactly the answer a missing job gives (404), so job ids cannot be probed. Ops and the system
     * see every job.
     */
    private void requireInScope(Job job, Actor actor) {
        boolean inScope = switch (actor.role().acting()) {
            case OPS, OPS_LEAD, FOUNDER, SYSTEM -> true; // acting() never returns the senior roles
            case BRAND_ADMIN, BRAND_MEMBER -> job.getBrandOrgId().equals(actor.orgId());
            case CREATOR -> actor.userId().equals(activeAssignment(job).map(Assignment::getCreatorId).orElse(null));
        };
        if (!inScope) {
            throw new JobNotFoundException(job.getId());
        }
    }

    private UUID applyStep(Job job, TransitionRule rule, Actor actor, String reason, UUID creatorId,
                           TransitionDetails details, UUID correlationId, UUID causationId,
                           Map<String, Object> factsOut) {
        Instant now = clock.instant();
        JobStatus from = job.getStatus();

        checkGuards(job, rule, details, now);
        Instant draftUploadedAt = checkDraft(job, rule, details);
        if (rule.action() == JobAction.ACCEPT && actor.role() == ActorRole.CREATOR
                && !eligibility.mayAcceptWork(actor.userId())) {
            throw new TransitionGuardException("Complete your profile (and make sure it is not paused) before "
                    + "accepting jobs");
        }
        ReasonCode reasonCode = reasonCodeFor(job, rule, details);
        boolean correction = rule.action() == JobAction.OPS_CORRECT_REASON;
        Map<String, Object> correctionFacts = new LinkedHashMap<>();
        UUID correctedCreator = null;
        UUID correctedAssignment = null;
        if (correction) {
            // The correction is about the creator of the decision it corrects, who may no longer be on the job.
            JobReadRepository.CodedEvent last = reads.lastCodedEvent(job.getId()).orElseThrow(
                    () -> new TransitionGuardException("This job has no reason code to correct"));
            if (last.reasonCode().equals(reasonCode.name())) {
                throw new TransitionGuardException("The reason code is already " + reasonCode);
            }
            correctedAssignment = last.assignmentId();
            correctedCreator = last.assignmentId() == null ? null : assignments.findById(last.assignmentId())
                    .map(Assignment::getCreatorId).orElse(null);
            correctionFacts.put("corrects", last.eventId().toString());
            correctionFacts.put("previousReasonCode", last.reasonCode());
        }
        Assignment assignment = correction ? null : changeAssignment(job, rule, creatorId, now);
        UUID assignmentId = correction ? correctedAssignment : assignment == null ? null : assignment.getId();
        UUID eventCreator = correction ? correctedCreator : assignment == null ? null : assignment.getCreatorId();
        Map<String, Object> facts = applyDeliveryFacts(job, rule, actor, details, assignmentId, now);
        facts.putAll(correctionFacts);
        factsOut.putAll(facts);
        if (rule.action() == JobAction.REQUEST_REVISION) {
            job.markFreeRevisionUsed();
        }
        if (rule.action() == JobAction.SUBMIT_DRAFT) {
            // Before the deadlines are cleared: on time against the deadline as it stood, by the upload time (the
            // checks and the preview take a little while; that is never the creator's delay).
            job.recordFirstDraft(draftUploadedAt);
        }
        job.moveTo(rule.to(), now);
        if (APPROVALS.contains(rule.action())) {
            drafts.approved(job.getId(), now);
        }
        if (correction) {
            // A correction changes the record, never the job: state and every clock stay exactly as they were.
        } else if (isExtension(rule.action())) {
            if (rule.action() == JobAction.REQUEST_EXTENSION) {
                job.markCreatorExtensionUsed();
            }
            JobDeadlines.extendProduction(job, now); // same state: keep the clock running, just move it
        } else {
            JobDeadlines.apply(job, now, calendar, reviewWorkingDays);
        }
        jobs.saveAndFlush(job); // optimistic lock check happens here; bumps version
        countAfterCommit(rule.action(), actor);

        UUID eventId = recorder.recordStateChange(job, assignmentId, eventCreator, from, rule.action(), actor, reason,
                reasonCode, job.getDeliveryIssueType(), facts, now, correlationId, causationId);
        recordOutcome(job, rule.action(), from, assignment, reasonCode, now, correlationId, eventId);
        return eventId;
    }

    private static final java.util.Set<JobAction> APPROVALS = java.util.EnumSet.of(JobAction.APPROVE,
            JobAction.AUTO_APPROVE, JobAction.RESOLVE_APPROVE);

    /**
     * F7: a video reaches the brand only through the media worker (checks passed, preview made), never by calling the
     * action directly; and the production deadline waits for a video uploaded in time that is still being checked.
     *
     * @return the upload time of the delivered video (SUBMIT_DRAFT / RESUBMIT / PREVIEW_READY), else null
     */
    private Instant checkDraft(Job job, TransitionRule rule, TransitionDetails details) {
        switch (rule.action()) {
            case SUBMIT_DRAFT, RESUBMIT, PREVIEW_READY -> {
                if (details.draftId() == null) {
                    throw new TransitionGuardException("Upload the video in the app: it is checked, then sent to the "
                            + "brand (POST /api/core/creator/jobs/" + job.getId() + "/drafts)");
                }
                return drafts.deliverable(job.getId(), details.draftId()).orElseThrow(() ->
                        new TransitionGuardException("This video has not passed its checks for this job"));
            }
            case PRODUCTION_EXPIRED -> {
                if (drafts.inFlightSince(job.getId(), job.getProductionDueAt())) {
                    throw new com.loofie.core.job.domain.JobExceptions.TransitionDeferredException(
                            "A video uploaded before the deadline is still being checked");
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Actions that end a creator's part early: the outcome says replaced, with the cause's reason code. */
    private static final java.util.Set<JobAction> REPLACED_BY = java.util.EnumSet.of(JobAction.PRODUCTION_EXPIRED,
            JobAction.CREATOR_WITHDRAW, JobAction.OPS_RELEASE_CREATOR, JobAction.RESOLVE_REPLACE, JobAction.RESOLVE_REFUND);

    /**
     * {@code job_outcome} for the AI's track record (DECISIONS.md section 9), in this transaction: when the job closes
     * (on time, revisions, disputed) or when the creator's part ends early (replaced, with the cause's code; the
     * matcher counts it only if the code is the creator's fault). Cancellations send none: the creator did not fail.
     */
    private void recordOutcome(Job job, JobAction action, JobStatus from, Assignment assignment, ReasonCode code,
                               Instant now, UUID correlationId, UUID causationId) {
        boolean closed = action == JobAction.CLOSE;
        boolean replaced = REPLACED_BY.contains(action) && !(action == JobAction.RESOLVE_REFUND
                && from != JobStatus.DISPUTED);
        if ((!closed && !replaced) || assignment == null) {
            return;
        }
        Boolean onTime = closed ? job.getFirstDraftOnTime() : Boolean.FALSE;
        if (onTime == null) {
            log.warn("Job {} closed without a recorded first draft; no job_outcome sent", job.getId());
            return;
        }
        recorder.recordOutcome(job, assignment.getId(), assignment.getCreatorId(), onTime,
                reads.countActions(job.getId(), assignment.getId(), JobAction.REQUEST_REVISION),
                reads.countActions(job.getId(), assignment.getId(), JobAction.REJECT) > 0, replaced, closed ? null : code, now,
                correlationId, causationId);
    }

    /** Business metric per action; counted only once the transaction really commits. */
    private void countAfterCommit(JobAction action, Actor actor) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meters.counter("loofie.job.transitions", "action", action.name(), "actor", actor.role().name())
                        .increment();
            }
        });
    }

    private static boolean isExtension(JobAction action) {
        return action == JobAction.REQUEST_EXTENSION || action == JobAction.OPS_EXTEND;
    }

    private static void checkGuards(Job job, TransitionRule rule, TransitionDetails details, Instant now) {
        switch (rule.action()) {
            case OPS_EXTEND -> {
                if (job.getProductionDueAt() == null) {
                    throw new TransitionGuardException("This job has no production deadline to extend");
                }
            }
            case REQUEST_EXTENSION -> {
                if (job.isCreatorExtensionUsed()) {
                    throw new TransitionGuardException("The creator's one extension is already used; ask ops");
                }
                if (job.getProductionDueAt() == null || !now.isBefore(job.getProductionDueAt())) {
                    throw new TransitionGuardException("An extension must be requested before the production deadline");
                }
            }
            // TODO(open-q): paid revisions go through a ChangeRequest (F7), not a second free revision.
            case REQUEST_REVISION -> {
                if (job.isFreeRevisionUsed()) {
                    throw new TransitionGuardException("The free revision has already been used");
                }
            }
            case REJECT -> {
                if (!job.isFreeRevisionUsed()) {
                    throw new TransitionGuardException("Reject is only allowed after the free revision");
                }
            }
            case START_DIGITAL -> {
                if (!job.isDigitalOnly()) {
                    throw new TransitionGuardException("Job needs a product; use MARK_SHIPPED");
                }
            }
            case MARK_SHIPPED, RESHIP -> {
                if (job.isDigitalOnly()) {
                    throw new TransitionGuardException("Digital-only job has no product to ship");
                }
                // Courier, tracking number / handover photo / link and duplicate numbers: see parcel().
            }
            case OPS_RESUME_PRODUCTION -> {
                if (job.getProductionPausedSeconds() == null) {
                    throw new TransitionGuardException("Production was not paused by a damage report; nothing to resume");
                }
            }
            case CONFIRM_RECEIVED -> {
                if (isBlank(details.photoRef())) {
                    throw new TransitionGuardException("Confirming receipt needs a photo of the product");
                }
                // Only a storage key we issue, never a URL or free text: ops opens it (stored-XSS / phishing risk).
                if (!Shipment.isReceiptPhotoKey(details.photoRef())) {
                    throw new TransitionGuardException("The receipt photo must be an uploaded photo key "
                            + "(receipts/<id>.jpg, .jpeg, .png, .heic or .webp)");
                }
            }
            case REPORT_DELIVERY_ISSUE -> {
                if (details.issueType() == null) {
                    throw new TransitionGuardException("Say what went wrong: NOT_RECEIVED, DAMAGED or WRONG_ITEM");
                }
            }
            case COURIER_DELIVERED -> {
                if (job.getCourierDeliveredAt() != null) {
                    throw new TransitionGuardException("The courier delivery is already recorded");
                }
            }
            case AUTO_START_PRODUCTION -> {
                // Never without courier proof: a slow courier must not make the creator look late.
                if (job.getCourierDeliveredAt() == null) {
                    throw new TransitionGuardException("No courier \"Delivered\" proof; production never auto-starts without it");
                }
                if (now.isBefore(job.getCourierDeliveredAt().plus(JobDeadlines.AUTO_START_AFTER_DELIVERED))) {
                    throw new TransitionGuardException("The creator still has 24 hours after delivery to report a problem");
                }
            }
            case CREATOR_WITHDRAW -> {
                if (job.getStatus() == JobStatus.CREATOR_ACCEPTED
                        && (job.getShipDueAt() == null || now.isBefore(job.getShipDueAt()))) {
                    throw new TransitionGuardException("The creator may withdraw only once the brand is late shipping");
                }
                if (job.getStatus() == JobStatus.DELIVERY_ISSUE
                        && (job.getMissingEscalateAt() == null || now.isBefore(job.getMissingEscalateAt()))) {
                    throw new TransitionGuardException("The creator may withdraw 7 days after the first shipment; ask ops sooner");
                }
            }
            // Timer actions: only once their deadline has really passed (a late or duplicate worker run is harmless).
            case ACCEPT_TIMEOUT -> requirePassed(job.getAcceptDueAt(), now, "accept window");
            case PRODUCTION_EXPIRED -> requirePassed(job.getProductionDueAt(), now, "production deadline");
            case AUTO_APPROVE -> requirePassed(job.getReviewDueAt(), now, "review window");
            case REPLACEMENT_EXPIRED -> requirePassed(job.getReplacementDueAt(), now, "replacement window");
            case OPS_RELEASE_CREATOR -> {
                if (details.reasonCode() == null) {
                    throw new TransitionGuardException("Releasing a creator needs a reason code");
                }
            }
            // A dispute decision says whose fault it was: it feeds the creator's track record (DECISIONS.md section 9).
            case RESOLVE_REPLACE -> {
                if (details.reasonCode() == null) {
                    throw new TransitionGuardException("Replacing the creator after a dispute needs a reason code");
                }
            }
            case RESOLVE_REFUND -> {
                if (job.getStatus() == JobStatus.DISPUTED && details.reasonCode() == null) {
                    throw new TransitionGuardException("Refunding a dispute needs a reason code");
                }
            }
            case OPS_CORRECT_REASON -> {
                if (details.reasonCode() == null) {
                    throw new TransitionGuardException("Say the corrected reason code");
                }
            }
            default -> {
                // no extra guard
            }
        }
        if (details.reasonCode() == ReasonCode.FALSE_CLAIM && rule.action() != JobAction.RESOLVE_FALSE_CLAIM) {
            throw new TransitionGuardException("FALSE_CLAIM is set only by resolving a false delivery claim");
        }
    }

    /** The reason code logged for the AI: fixed by the action where the fault is known, else what ops chose. */
    private static ReasonCode reasonCodeFor(Job job, TransitionRule rule, TransitionDetails details) {
        return switch (rule.action()) {
            case RESOLVE_FALSE_CLAIM -> ReasonCode.FALSE_CLAIM;
            // Deadline and extension passed with no draft: the creator went silent (counts against them).
            case PRODUCTION_EXPIRED -> ReasonCode.CREATOR_UNRESPONSIVE;
            case CREATOR_WITHDRAW -> job.getStatus() == JobStatus.CREATOR_ACCEPTED
                    ? ReasonCode.BRAND_LATE_SHIP
                    : job.getDeliveryIssueType() == null ? ReasonCode.OTHER : job.getDeliveryIssueType().withdrawalReason();
            default -> details.reasonCode();
        };
    }

    /** How far ahead of our clock a reported delivery time may be (courier and server clocks differ a little). */
    static final java.time.Duration DELIVERY_CLOCK_SKEW = java.time.Duration.ofMinutes(5);

    /**
     * F6 facts that ride along with a transition: shipments, courier proof, receipt, issues. Returns the facts
     * to add to the event (shipment attempt, delivery source...), so the log says which parcel it was about.
     */
    private Map<String, Object> applyDeliveryFacts(Job job, TransitionRule rule, Actor actor, TransitionDetails details,
                                                   UUID assignmentId, Instant now) {
        Map<String, Object> facts = new LinkedHashMap<>();
        switch (rule.action()) {
            case INVITE, APPROVE_SWAP -> job.resetForNewCreator(); // fresh shipment and their own extension
            case MARK_SHIPPED, RESHIP -> {
                int before = (int) shipments.countByJobId(job.getId());
                // One reship goes through normally; a second delivery problem is for ops (DECISIONS.md section 5).
                if (rule.action() == JobAction.RESHIP && !actor.role().isOps() && before >= MAX_BRAND_SHIPMENTS) {
                    throw new TransitionGuardException("This job has already been reshipped once; ops handles a "
                            + "second delivery problem (reship or replace the creator)");
                }
                int attempt = before + 1;
                Shipment.Parcel parcel = parcel(job, details, assignmentId);
                shipments.save(Shipment.create(Ids.newId(), job.getId(), assignmentId, attempt, parcel, now,
                        actor.userId()));
                job.recordShipped(now);
                job.dropPausedProduction(); // a new product: the 120 hours restart on its receipt
                facts.put("shipmentAttempt", attempt);
                facts.put("courierCode", parcel.courierCode());
                if (parcel.trackingReuse() != null) {
                    facts.put("trackingReused", parcel.trackingReuse()); // ops checks it (usually a copy-paste slip)
                }
            }
            case COURIER_DELIVERED -> {
                Shipment latest = latestShipment(job);
                DeliverySource source = deliverySource(actor, details);
                Instant deliveredAt = deliveredAt(details, latest, now);
                latest.markCourierDelivered(deliveredAt, actor.userId(), source, now);
                job.recordCourierDelivered(deliveredAt); // the creator's 24h window counts from here
                facts.put("shipmentAttempt", latest.getAttempt());
                facts.put("deliverySource", source.name());
                facts.put("deliveredAt", deliveredAt.toString());
            }
            case CONFIRM_RECEIVED -> {
                Shipment latest = latestShipment(job);
                Shipment received = receivedShipment(job, details, latest);
                received.markReceived(now, details.photoRef());
                job.clearDeliveryIssue();
                facts.put("shipmentAttempt", received.getAttempt());
                if (received.getAttempt() < latest.getAttempt()) {
                    // The first parcel turned up after a reship: ops should stop or recall the reship.
                    facts.put("olderShipmentReceived", true);
                }
            }
            case REPORT_DELIVERY_ISSUE -> {
                if (job.getStatus() == JobStatus.IN_PRODUCTION) {
                    damageAfterReceipt(job, actor, details, now, facts);
                }
                job.recordDeliveryIssue(details.issueType(), now);
            }
            // The late-damage window of a creator who never tapped Received counts from here.
            case AUTO_START_PRODUCTION -> job.markAutoStarted(now);
            case RESOLVE_FALSE_CLAIM -> job.clearDeliveryIssue();
            case OPS_RESUME_PRODUCTION -> {
                facts.put("resumedSeconds", job.getProductionPausedSeconds());
                job.clearDeliveryIssue(); // JobDeadlines gives back the time that was left
            }
            default -> {
                // nothing to record
            }
        }
        return facts;
    }

    /** The first shipment plus one reship; after that only ops reships. */
    static final int MAX_BRAND_SHIPMENTS = 2;
    private static final int MAX_COURIER_NAME = 64;
    private static final int MAX_TRACKING_LINK = 300;

    /**
     * What was shipped, checked against the courier table: TRACKED needs a number, LOCAL a handover photo, OTHER a
     * name and an https link. A number is checked against other jobs (duplicate rules, DECISIONS.md section 5).
     */
    private Shipment.Parcel parcel(Job job, TransitionDetails d, UUID assignmentId) {
        Courier courier = courier(d);
        String number = isBlank(d.trackingNumber()) ? null : d.trackingNumber().trim();
        String name = courier.name();
        String link = null;
        String handover = null;
        switch (courier.kind()) {
            case TRACKED -> {
                if (number == null) {
                    throw new TransitionGuardException("Shipping with " + courier.name() + " needs the tracking number");
                }
            }
            case LOCAL -> {
                if (!Shipment.isHandoverPhotoKey(d.handoverPhotoRef())) {
                    throw new TransitionGuardException("Local delivery has no tracking number, so it needs a handover "
                            + "photo (handovers/<id>.jpg, .jpeg, .png, .heic or .webp)");
                }
                handover = d.handoverPhotoRef();
                if (!isBlank(d.courier())) {
                    name = d.courier().trim(); // e.g. "Porter"
                }
            }
            case OTHER -> {
                // "Shipped" always needs a number (DECISIONS.md section 5); "Other" adds its name and a link on top.
                if (number == null) {
                    throw new TransitionGuardException("Shipping needs the tracking number");
                }
                if (isBlank(d.courier())) {
                    throw new TransitionGuardException("For a courier that is not in the list, give its name");
                }
                link = d.trackingLink() == null ? null : d.trackingLink().trim();
                if (link == null || !link.startsWith("https://") || link.length() > MAX_TRACKING_LINK
                        || link.chars().anyMatch(Character::isWhitespace)) {
                    throw new TransitionGuardException("For a courier that is not in the list, give its tracking link "
                            + "(https://...), so ops can check the parcel");
                }
                name = d.courier().trim();
            }
        }
        if (name.length() > MAX_COURIER_NAME) {
            throw new TransitionGuardException("The courier name is too long (at most " + MAX_COURIER_NAME + " characters)");
        }
        String reuse = number == null ? null : trackingReuse(job, number, assignmentId);
        return new Shipment.Parcel(courier.code(), name, number, link, handover, reuse);
    }

    /** The chosen courier; clients that still send only a name are matched by name. */
    private Courier courier(TransitionDetails d) {
        if (!isBlank(d.courierCode())) {
            return couriers.find(d.courierCode().trim().toUpperCase()).filter(Courier::active)
                    .orElseThrow(() -> new TransitionGuardException("Unknown courier " + d.courierCode()
                            + "; pick one from the list, or OTHER with its name and tracking link"));
        }
        if (!isBlank(d.courier())) {
            return couriers.findByName(d.courier()).orElseThrow(() -> new TransitionGuardException(
                    "Courier \"" + d.courier().trim() + "\" is not in the list; pick one, or OTHER with its name and "
                            + "tracking link"));
        }
        throw new TransitionGuardException("Shipping needs a courier");
    }

    /**
     * Duplicate tracking numbers, compared normalised: another brand's job → blocked; the same brand but another
     * creator → allowed and flagged; the same brand and creator (one parcel, two jobs) → allowed. Under a lock on the
     * number, so two brands entering it at the same moment cannot both pass.
     */
    private String trackingReuse(Job job, String number, UUID assignmentId) {
        String normalised = Courier.normaliseTrackingNumber(number);
        trackingNumbers.lock(normalised);
        UUID creator = assignments.findById(assignmentId).map(Assignment::getCreatorId).orElse(null);
        String reuse = null;
        for (TrackingNumberRepository.Use use : trackingNumbers.uses(normalised)) {
            if (use.jobId().equals(job.getId())) {
                continue; // this job's own earlier attempt
            }
            if (!use.brandOrgId().equals(job.getBrandOrgId())) {
                throw new TransitionGuardException("This tracking number is already used. Check it or contact support.");
            }
            if (!use.creatorId().equals(creator)) {
                reuse = "SAME_BRAND_OTHER_CREATOR";
            }
        }
        return reuse;
    }

    /**
     * Damage found after "Received" (DECISIONS.md section 5): the creator once per job, within 24 hours of tapping
     * Received, with a photo or video; ops at any time. The production clock stops and keeps the time left.
     */
    private void damageAfterReceipt(Job job, Actor actor, TransitionDetails details, Instant now,
                                    Map<String, Object> facts) {
        Shipment received = shipments.findByJobIdOrderByAttempt(job.getId()).stream()
                .filter(Shipment::isReceived)
                .max(java.util.Comparator.comparing(Shipment::getReceivedAt))
                .orElse(null);
        if (actor.role() == ActorRole.CREATOR) {
            if (details.issueType() != com.loofie.core.job.domain.DeliveryIssueType.DAMAGED) {
                throw new TransitionGuardException("After receipt only damage can be reported here; ask ops");
            }
            if (job.isLateDamageReported()) {
                throw new TransitionGuardException("Damage after receipt can be reported once per job; ask ops");
            }
            // The window opens when she got the product: her "Received" tap, or the automatic start if she never tapped.
            Instant opened = latest(received == null ? null : received.getReceivedAt(), job.getAutoStartedAt());
            if (opened == null || now.isAfter(opened.plus(JobDeadlines.LATE_DAMAGE_WINDOW))) {
                throw new TransitionGuardException("Damage can be reported within 24 hours of receiving the product "
                        + "(tapping Received, or the clock starting on its own); after that, ask ops");
            }
            if (!Shipment.isDamageEvidenceKey(details.evidenceRef())) {
                throw new TransitionGuardException("Reporting damage needs a photo or short video of it "
                        + "(damage/<id>.jpg, .jpeg, .png, .heic, .webp, .mp4 or .mov)");
            }
            job.markLateDamageReported();
        } else if (details.evidenceRef() != null && !Shipment.isDamageEvidenceKey(details.evidenceRef())) {
            throw new TransitionGuardException("The evidence must be an uploaded photo or video key (damage/<id>...)");
        }
        // The parcel it is about: the one she confirmed, else (auto-started) the latest one, which courier proof covers.
        Shipment about = received != null ? received : shipments.findTopByJobIdOrderByAttemptDesc(job.getId()).orElse(null);
        if (about != null) {
            about.markDamageReported(now, details.evidenceRef());
        }
        job.pauseProduction(now);
        facts.put("afterReceipt", true);
        facts.put("pausedSeconds", job.getProductionPausedSeconds());
    }

    /** Ops must say what the "Delivered" fact is based on; only the tracking integration (SYSTEM) may say TRACKING_API. */
    private static DeliverySource deliverySource(Actor actor, TransitionDetails details) {
        DeliverySource source = details.deliverySource();
        if (actor.role() == ActorRole.SYSTEM) {
            return source != null ? source : DeliverySource.TRACKING_API;
        }
        if (source == null) {
            throw new TransitionGuardException("Say where \"Delivered\" comes from: TRACKING_PAGE or COURIER_CALL");
        }
        if (source == DeliverySource.TRACKING_API) {
            throw new TransitionGuardException("TRACKING_API is reported by the tracking integration only");
        }
        return source;
    }

    /** The courier's delivery time: given by ops (it may be recorded late), never in the future or before shipping. */
    private static Instant deliveredAt(TransitionDetails details, Shipment shipment, Instant now) {
        Instant at = details.deliveredAt() != null ? details.deliveredAt() : now;
        if (at.isAfter(now.plus(DELIVERY_CLOCK_SKEW))) {
            throw new TransitionGuardException("The delivery time cannot be in the future");
        }
        if (at.isAfter(now)) {
            at = now; // a few minutes of clock difference
        }
        if (at.isBefore(shipment.getShippedAt())) {
            throw new TransitionGuardException("The delivery time is before the parcel was shipped");
        }
        return at;
    }

    /** Which parcel the creator got: the latest by default, or an earlier attempt that turned up after a reship. */
    private Shipment receivedShipment(Job job, TransitionDetails details, Shipment latest) {
        Shipment received = details.shipmentAttempt() == null ? latest
                : shipments.findByJobIdAndAttempt(job.getId(), details.shipmentAttempt())
                        .orElseThrow(() -> new TransitionGuardException(
                                "This job has no shipment attempt " + details.shipmentAttempt()));
        if (received.isReceived()) {
            throw new TransitionGuardException("Shipment attempt " + received.getAttempt() + " is already confirmed");
        }
        return received;
    }

    private Shipment latestShipment(Job job) {
        return shipments.findTopByJobIdOrderByAttemptDesc(job.getId())
                .orElseThrow(() -> new TransitionGuardException("Job " + job.getId() + " has no shipment"));
    }

    /** Opens or closes the creator assignment where the transition requires it. Returns the relevant assignment id. */
    /** The assignment this change is about (opened, closed or unchanged), or null if the job has none. */
    private Assignment changeAssignment(Job job, TransitionRule rule, UUID creatorId, Instant now) {
        Optional<Assignment> active = activeAssignment(job);
        switch (rule.action()) {
            case INVITE, APPROVE_SWAP -> {
                if (creatorId == null) {
                    throw new TransitionGuardException(rule.action() + " needs the creatorId to invite");
                }
                if (active.isPresent()) {
                    throw new TransitionGuardException("Job " + job.getId() + " already has an active creator");
                }
                Assignment opened = Assignment.open(Ids.newId(), job.getId(), creatorId, now);
                assignments.save(opened);
                return opened;
            }
            case DECLINE, ACCEPT_TIMEOUT, PRODUCTION_EXPIRED, RESOLVE_REPLACE, RESOLVE_REFUND, CANCEL,
                 CREATOR_WITHDRAW, OPS_RELEASE_CREATOR -> {
                active.ifPresent(a -> {
                    a.close(rule.action().name(), now);
                    assignments.save(a);
                });
                return active.orElse(null);
            }
            default -> {
                return active.orElse(null);
            }
        }
    }

    private Optional<Assignment> activeAssignment(Job job) {
        return assignments.findByJobIdAndStatus(job.getId(), Assignment.Status.ACTIVE);
    }

    private static void requirePassed(Instant deadline, Instant now, String what) {
        if (deadline == null || now.isBefore(deadline)) {
            throw new TransitionGuardException("The " + what + " has not passed yet");
        }
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
