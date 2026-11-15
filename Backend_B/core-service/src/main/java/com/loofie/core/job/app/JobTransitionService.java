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
import com.loofie.core.job.domain.DeliverySource;
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

    public JobTransitionService(JobRepository jobs, AssignmentRepository assignments, ShipmentRepository shipments,
                                JobEventRecorder recorder, WorkingCalendar calendar, Clock clock, MeterRegistry meters) {
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
        UUID eventId = applyStep(job, rule, cmd.actor(), cmd.reason(), cmd.creatorId(), cmd.details(), correlationId,
                null);
        eventIds.add(eventId);

        // Automatic follow-ups (e.g. PRODUCT_RECEIVED -> IN_PRODUCTION) run as SYSTEM in the same transaction.
        for (JobAction next = followUpFor(job, rule); next != null; next = followUpFor(job, rule)) {
            rule = requireRule(job.getStatus(), next);
            eventId = applyStep(job, rule, Actor.system(), null, null, TransitionDetails.NONE, correlationId, eventId);
            eventIds.add(eventId);
        }
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

    private void authorize(Job job, TransitionRule rule, Actor actor, String reason, TransitionDetails details) {
        if (!rule.roles().contains(actor.role())) {
            throw new ActorNotAllowedException(actor.role() + " may not " + rule.action() + " (allowed: " + rule.roles() + ")");
        }
        if (actor.role() == ActorRole.OPS && (reason == null || reason.isBlank())) {
            throw new TransitionGuardException("Ops actions need a reason");
        }
        // Reason codes go to the AI log and can count against a creator, so only ops may choose one.
        if (details.reasonCode() != null && actor.role() != ActorRole.OPS) {
            throw new ActorNotAllowedException("Only ops may set a reason code");
        }
    }

    /**
     * A brand sees only its own jobs, a creator only the job they are currently assigned to. Anyone else
     * gets exactly the answer a missing job gives (404), so job ids cannot be probed. Ops and the system
     * see every job.
     */
    private void requireInScope(Job job, Actor actor) {
        boolean inScope = switch (actor.role()) {
            case OPS, SYSTEM -> true;
            case BRAND_ADMIN, BRAND_MEMBER -> job.getBrandOrgId().equals(actor.orgId());
            case CREATOR -> actor.userId().equals(activeAssignment(job).map(Assignment::getCreatorId).orElse(null));
        };
        if (!inScope) {
            throw new JobNotFoundException(job.getId());
        }
    }

    private UUID applyStep(Job job, TransitionRule rule, Actor actor, String reason, UUID creatorId,
                           TransitionDetails details, UUID correlationId, UUID causationId) {
        Instant now = clock.instant();
        JobStatus from = job.getStatus();

        checkGuards(job, rule, details, now);
        ReasonCode reasonCode = reasonCodeFor(job, rule, details);
        Assignment assignment = changeAssignment(job, rule, creatorId, now);
        UUID assignmentId = assignment == null ? null : assignment.getId();
        Map<String, Object> facts = applyDeliveryFacts(job, rule, actor, details, assignmentId, now);
        if (rule.action() == JobAction.REQUEST_REVISION) {
            job.markFreeRevisionUsed();
        }
        job.moveTo(rule.to(), now);
        if (isExtension(rule.action())) {
            if (rule.action() == JobAction.REQUEST_EXTENSION) {
                job.markCreatorExtensionUsed();
            }
            JobDeadlines.extendProduction(job, now); // same state: keep the clock running, just move it
        } else {
            JobDeadlines.apply(job, now, calendar);
        }
        jobs.saveAndFlush(job); // optimistic lock check happens here; bumps version
        countAfterCommit(rule.action(), actor);

        return recorder.recordStateChange(job, assignmentId, assignment == null ? null : assignment.getCreatorId(), from,
                rule.action(), actor, reason, reasonCode,
                job.getDeliveryIssueType(), facts, now, correlationId, causationId);
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
                if (isBlank(details.courier()) || isBlank(details.trackingNumber())) {
                    throw new TransitionGuardException("Shipping needs the courier name and tracking number");
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
                int attempt = (int) shipments.countByJobId(job.getId()) + 1;
                shipments.save(Shipment.create(Ids.newId(), job.getId(), assignmentId, attempt, details.courier(),
                        details.trackingNumber(), now, actor.userId()));
                job.recordShipped(now);
                facts.put("shipmentAttempt", attempt);
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
            case REPORT_DELIVERY_ISSUE -> job.recordDeliveryIssue(details.issueType(), now);
            case RESOLVE_FALSE_CLAIM -> job.clearDeliveryIssue();
            default -> {
                // nothing to record
            }
        }
        return facts;
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
