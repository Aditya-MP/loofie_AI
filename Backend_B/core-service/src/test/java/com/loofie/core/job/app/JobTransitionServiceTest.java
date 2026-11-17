package com.loofie.core.job.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.order.app.OrderSubmissionService;
import com.loofie.core.order.app.OrderSubmissionService.Pick;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

@IntegrationTest
class JobTransitionServiceTest {

    @Autowired JobTransitionService engine;
    @Autowired OrderSubmissionService orders;
    @Autowired JobRepository jobs;
    @Autowired AssignmentRepository assignments;
    @Autowired JobFixtures fixtures;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    final UUID brandOrg = UUID.randomUUID();
    final Actor brand = Actor.brandAdmin(UUID.randomUUID(), brandOrg);
    final Actor ops = Actor.ops(UUID.randomUUID());

    @Test
    void physicalProductJobRunsFromOrderToClosed() {
        UUID creator = UUID.randomUUID();
        UUID jobId = submitOneJob(false);
        Actor creatorActor = Actor.creator(creator);

        engine.transition(jobId, JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        engine.transition(TransitionCommand.of(jobId, JobAction.INVITE, ops, "best fit for brief").withCreator(creator));
        assertThat(job(jobId).getAcceptDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.ACCEPT_WINDOW));

        engine.transition(jobId, JobAction.ACCEPT, creatorActor, null);
        assertThat(job(jobId).getAcceptDueAt()).isNull();

        engine.transition(TransitionCommand.of(jobId, JobAction.MARK_SHIPPED, brand, null)
                .with(TransitionDetails.shipment("Delhivery", JobFixtures.trackingNumber())));
        assertThat(job(jobId).getReceiptOpsCheckAt()).isEqualTo(clock.instant().plus(JobDeadlines.RECEIPT_OPS_CHECK_AFTER));
        assertThat(job(jobId).getShipDueAt()).isNull();

        clock.advance(Duration.ofHours(30));
        TransitionResult received = engine.transition(TransitionCommand.of(jobId, JobAction.CONFIRM_RECEIVED, creatorActor,
                null).with(TransitionDetails.photo(JobFixtures.photoKey())));
        assertThat(received.to()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(received.eventIds()).hasSize(2); // SHIPPED -> RECEIVED, then RECEIVED -> IN_PRODUCTION
        assertThat(job(jobId).getProductionDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.PRODUCTION_WINDOW));
        assertThat(job(jobId).getReceiptOpsCheckAt()).isNull();

        engine.transition(jobId, JobAction.SUBMIT_DRAFT, creatorActor, null);
        engine.transition(jobId, JobAction.PREVIEW_READY, Actor.system(), null);
        assertThat(job(jobId).getReviewDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.REVIEW_WINDOW));

        engine.transition(jobId, JobAction.APPROVE, brand, null);
        engine.transition(jobId, JobAction.PAYOUT_QUEUED, Actor.system(), null);
        TransitionResult closed = engine.transition(jobId, JobAction.PAYOUT_COMPLETED, Actor.system(), null);

        assertThat(closed.to()).isEqualTo(JobStatus.CLOSED); // closes as soon as the creator is paid
        assertThat(closed.eventIds()).hasSize(2);           // PENDING -> PAID_OUT, then PAID_OUT -> CLOSED
        assertThat(job(jobId).getReviewDueAt()).isNull();
        assertThat(eventPath(jobId)).containsExactly(
                "DRAFT_BRIEF>AWAITING_PAYMENT", "AWAITING_PAYMENT>PAID", "PAID>CREATOR_INVITED",
                "CREATOR_INVITED>CREATOR_ACCEPTED", "CREATOR_ACCEPTED>PRODUCT_SHIPPED",
                "PRODUCT_SHIPPED>PRODUCT_RECEIVED", "PRODUCT_RECEIVED>IN_PRODUCTION",
                "IN_PRODUCTION>DRAFT_SUBMITTED", "DRAFT_SUBMITTED>IN_REVIEW", "IN_REVIEW>APPROVED",
                "APPROVED>PAYOUT_PENDING", "PAYOUT_PENDING>PAID_OUT", "PAID_OUT>CLOSED");
        // 13 state changes + the job_outcome sent when it closes (on time, no revisions, not disputed).
        assertThat(fixtures.outboxCount(jobId)).isEqualTo(14);
        var outcome = jdbc.sql("SELECT envelope->'payload' AS p FROM outbox WHERE message_key = ? AND event_type = 'JobOutcome'")
                .param(jobId.toString()).query(String.class).single();
        assertThat(outcome).contains("\"onTime\": true", "\"revisions\": 0", "\"disputed\": false", "\"replaced\": false");
        assertOutboxMirrorsEvents(jobId);
    }

    @Test
    void digitalOnlyJobStartsProductionWhenTheCreatorAccepts() {
        UUID creator = UUID.randomUUID();
        UUID jobId = submitOneJob(true);
        engine.transition(jobId, JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        engine.transition(TransitionCommand.of(jobId, JobAction.INVITE, ops, "invite").withCreator(creator));

        TransitionResult accepted = engine.transition(jobId, JobAction.ACCEPT, Actor.creator(creator), null);

        assertThat(accepted.to()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(accepted.eventIds()).hasSize(2); // INVITED -> ACCEPTED, then ACCEPTED -> IN_PRODUCTION
        assertThat(job(jobId).getProductionDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.PRODUCTION_WINDOW));
        assertThat(job(jobId).getShipDueAt()).isNull();
        assertThat(eventPath(jobId)).endsWith("CREATOR_INVITED>CREATOR_ACCEPTED", "CREATOR_ACCEPTED>IN_PRODUCTION");
        assertThat(actionsOf(jobId)).endsWith("ACCEPT", "START_DIGITAL");
        assertOutboxMirrorsEvents(jobId);
    }

    @Test
    void physicalJobWaitsForShippingAfterAccept() {
        UUID creator = UUID.randomUUID();
        UUID jobId = submitOneJob(false);
        engine.transition(jobId, JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        engine.transition(TransitionCommand.of(jobId, JobAction.INVITE, ops, "invite").withCreator(creator));

        TransitionResult accepted = engine.transition(jobId, JobAction.ACCEPT, Actor.creator(creator), null);

        assertThat(accepted.to()).isEqualTo(JobStatus.CREATOR_ACCEPTED);
        assertThat(accepted.eventIds()).hasSize(1);
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.START_DIGITAL, Actor.system(), null))
                .isInstanceOf(TransitionGuardException.class);
    }

    @Test
    void brandMemberCanRequestARevisionButNotApprove() {
        Seeded seeded = fixtures.jobIn(JobStatus.IN_REVIEW);
        Actor member = Actor.brandMember(UUID.randomUUID(), seeded.brandOrgId());

        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.APPROVE, member, null))
                .isInstanceOf(ActorNotAllowedException.class);

        TransitionResult r = engine.transition(seeded.jobId(), JobAction.REQUEST_REVISION, member, null);
        assertThat(r.to()).isEqualTo(JobStatus.REVISION_REQUESTED);
        assertThat(job(seeded.jobId()).isFreeRevisionUsed()).isTrue();
    }

    @Test
    void creatorGetsOneAutomaticExtensionBeforeTheDeadline() {
        UUID creator = UUID.randomUUID();
        UUID jobId = jobInProductionWith(creator);
        Instant due = job(jobId).getProductionDueAt();

        clock.advance(Duration.ofHours(100));
        TransitionResult r = engine.transition(jobId, JobAction.REQUEST_EXTENSION, Actor.creator(creator), null);

        assertThat(r.to()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(job(jobId).getProductionDueAt()).isEqualTo(due.plus(JobDeadlines.EXTENSION));
        assertThat(actionsOf(jobId)).endsWith("REQUEST_EXTENSION");

        assertThatThrownBy(() -> engine.transition(jobId, JobAction.REQUEST_EXTENSION, Actor.creator(creator), null))
                .isInstanceOf(TransitionGuardException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void replacementCreatorGetsTheirOwnExtension() {
        UUID first = UUID.randomUUID();
        UUID jobId = jobInProductionWith(first);
        engine.transition(jobId, JobAction.REQUEST_EXTENSION, Actor.creator(first), null);
        clock.set(job(jobId).getProductionDueAt());
        engine.transition(jobId, JobAction.PRODUCTION_EXPIRED, Actor.system(), null);

        UUID second = UUID.randomUUID();
        engine.transition(TransitionCommand.of(jobId, JobAction.APPROVE_SWAP, brand, null).withCreator(second));
        assertThat(job(jobId).isCreatorExtensionUsed()).isFalse();

        engine.transition(jobId, JobAction.ACCEPT, Actor.creator(second), null); // digital-only: straight to production
        engine.transition(jobId, JobAction.REQUEST_EXTENSION, Actor.creator(second), null);
        assertThat(job(jobId).isCreatorExtensionUsed()).isTrue();
    }

    @Test
    void creatorCannotExtendAfterTheDeadline() {
        UUID creator = UUID.randomUUID();
        UUID jobId = jobInProductionWith(creator);

        clock.advance(JobDeadlines.PRODUCTION_WINDOW.plus(Duration.ofMinutes(1)));
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.REQUEST_EXTENSION, Actor.creator(creator), null))
                .isInstanceOf(TransitionGuardException.class)
                .hasMessageContaining("before the production deadline");
    }

    @Test
    void extendingAJobWithNoDeadlineIsARuleErrorNotACrash() {
        Seeded broken = fixtures.jobIn(JobStatus.IN_PRODUCTION); // bad data: no production deadline
        assertThatThrownBy(() -> engine.transition(broken.jobId(), JobAction.OPS_EXTEND, ops, "creator asked"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("no production deadline");
    }

    @Test
    void laterExtensionsAreOpsOnlyAndCountFromNowIfLate() {
        UUID creator = UUID.randomUUID();
        UUID jobId = jobInProductionWith(creator);
        engine.transition(jobId, JobAction.REQUEST_EXTENSION, Actor.creator(creator), null);

        clock.advance(JobDeadlines.PRODUCTION_WINDOW.plus(JobDeadlines.EXTENSION).plus(Duration.ofHours(5)));
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.OPS_EXTEND, Actor.creator(creator), null))
                .isInstanceOf(ActorNotAllowedException.class);

        engine.transition(jobId, JobAction.OPS_EXTEND, ops, "courier strike in creator's city");
        assertThat(job(jobId).getProductionDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.EXTENSION));
    }

    @Test
    void digitalOnlyJobCannotBeMarkedShipped() {
        Seeded seeded = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED, true, false);

        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.MARK_SHIPPED,
                Actor.brandAdmin(UUID.randomUUID(), seeded.brandOrgId()), null))
                .isInstanceOf(TransitionGuardException.class);
    }

    @Test
    void declineClosesAssignmentAndSwapKeepsTheSameJob() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID jobId = submitOneJob(false);
        engine.transition(jobId, JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        engine.transition(TransitionCommand.of(jobId, JobAction.INVITE, ops, "invite").withCreator(first));
        engine.transition(jobId, JobAction.DECLINE, Actor.creator(first), null);

        assertThat(assignments.findByJobIdAndStatus(jobId, Assignment.Status.ACTIVE)).isEmpty();

        engine.transition(TransitionCommand.of(jobId, JobAction.APPROVE_SWAP, brand, null).withCreator(second));
        List<Assignment> history = assignments.findByJobIdOrderByOpenedAt(jobId);
        assertThat(history).extracting(Assignment::getCreatorId).containsExactly(first, second);
        assertThat(history).extracting(Assignment::getStatus)
                .containsExactly(Assignment.Status.CLOSED, Assignment.Status.ACTIVE);
        assertThat(fixtures.status(jobId)).isEqualTo("CREATOR_INVITED");

        // The declined creator can no longer act on the job.
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.ACCEPT, Actor.creator(first), null))
                .isInstanceOf(JobNotFoundException.class); // no longer their job: they see nothing
    }

    @Test
    void wrongRoleWrongBrandAndWrongCreatorAreRejected() {
        Seeded seeded = fixtures.jobIn(JobStatus.IN_REVIEW);

        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.APPROVE, Actor.creator(seeded.creatorId()), null))
                .isInstanceOf(ActorNotAllowedException.class);
        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.APPROVE,
                Actor.brandAdmin(UUID.randomUUID(), UUID.randomUUID()), null))
                .isInstanceOf(JobNotFoundException.class); // another brand: indistinguishable from a missing job
        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.APPROVE,
                Actor.brandMember(UUID.randomUUID(), seeded.brandOrgId()), null))
                .isInstanceOf(ActorNotAllowedException.class);

        Seeded inProduction = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        assertThatThrownBy(() -> engine.transition(inProduction.jobId(), JobAction.SUBMIT_DRAFT,
                Actor.creator(UUID.randomUUID()), null))
                .isInstanceOf(JobNotFoundException.class); // not the assigned creator

        assertThat(fixtures.eventCount(seeded.jobId())).isZero();
        assertThat(fixtures.eventCount(inProduction.jobId())).isZero();
    }

    @Test
    void opsActionsNeedAReason() {
        // Unpaid: no money moves, so ops cancels directly (with a reason).
        Seeded seeded = fixtures.jobIn(JobStatus.AWAITING_PAYMENT);
        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.CANCEL, ops, " "))
                .isInstanceOf(TransitionGuardException.class);
        assertThat(engine.transition(seeded.jobId(), JobAction.CANCEL, ops, "brand withdrew").to())
                .isEqualTo(JobStatus.CANCELLED);
        // Paid: money moves, so only through an approved refund request.
        Seeded paid = fixtures.jobIn(JobStatus.PAID);
        assertThatThrownBy(() -> engine.transition(paid.jobId(), JobAction.CANCEL, ops, "brand withdrew"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("refund request");
    }

    @Test
    void oneFreeRevisionThenRejectOpensDispute() {
        Seeded seeded = fixtures.jobIn(JobStatus.IN_REVIEW);
        Actor owner = Actor.brandAdmin(UUID.randomUUID(), seeded.brandOrgId());
        Actor creator = Actor.creator(seeded.creatorId());

        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.REJECT, owner, null))
                .isInstanceOf(TransitionGuardException.class);

        engine.transition(seeded.jobId(), JobAction.REQUEST_REVISION, owner, null);
        engine.transition(seeded.jobId(), JobAction.RESUBMIT, creator, null);
        engine.transition(seeded.jobId(), JobAction.PREVIEW_READY, Actor.system(), null);

        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.REQUEST_REVISION, owner, null))
                .isInstanceOf(TransitionGuardException.class);
        assertThat(engine.transition(seeded.jobId(), JobAction.REJECT, owner, null).to()).isEqualTo(JobStatus.DISPUTED);
    }

    @Test
    void orderGuardsMinimumAndNewCreatorCap() {
        assertThatThrownBy(() -> orders.submit(brandOrg, brand, List.of(new Pick(false, 14_999_00L, false))))
                .isInstanceOf(TransitionGuardException.class);
        assertThatThrownBy(() -> orders.submit(brandOrg, brand,
                List.of(new Pick(false, 5_000_01L, true), new Pick(false, 10_000_00L, false))))
                .isInstanceOf(TransitionGuardException.class);
        assertThat(orders.submit(brandOrg, brand,
                List.of(new Pick(false, 5_000_00L, true), new Pick(true, 10_000_00L, false))).jobIds()).hasSize(2);
    }

    @Test
    void brandClickAndAutoApproveRaceProducesExactlyOneApproval() throws Exception {
        Seeded seeded = fixtures.jobIn(JobStatus.IN_REVIEW);
        fixtures.setDeadline(seeded.jobId(), "review_due_at", clock.instant().minusSeconds(1)); // auto-approve is due
        Actor owner = Actor.brandAdmin(UUID.randomUUID(), seeded.brandOrgId());
        CountDownLatch go = new CountDownLatch(1);

        List<Callable<Boolean>> racers = List.of(
                () -> { go.await(); return attempt(() -> engine.transition(seeded.jobId(), JobAction.APPROVE, owner, null)); },
                () -> { go.await(); return attempt(() -> engine.transition(seeded.jobId(), JobAction.AUTO_APPROVE, Actor.system(), null)); });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> r : racers) {
                futures.add(pool.submit(r));
            }
            go.countDown();
            long wins = 0;
            for (Future<Boolean> f : futures) {
                wins += f.get() ? 1 : 0;
            }
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(fixtures.status(seeded.jobId())).isEqualTo("APPROVED");
        assertThat(fixtures.eventCount(seeded.jobId())).isEqualTo(1);
    }

    private static boolean attempt(Runnable r) {
        try {
            r.run();
            return true;
        } catch (ObjectOptimisticLockingFailureException | IllegalTransitionException lost) {
            return false;
        }
    }

    private UUID submitOneJob(boolean digitalOnly) {
        return orders.submit(brandOrg, brand, List.of(new Pick(digitalOnly, 15_000_00L, false))).jobIds().getFirst();
    }

    private Job job(UUID id) {
        return jobs.findById(id).orElseThrow();
    }

    private List<String> eventPath(UUID jobId) {
        return jdbc.sql("SELECT from_state, to_state FROM job_events WHERE job_id = ? ORDER BY job_version")
                .param(jobId)
                .query((rs, n) -> rs.getString(1) + ">" + rs.getString(2))
                .list();
    }

    /** Digital-only job taken through the real engine up to IN_PRODUCTION with this creator assigned. */
    private UUID jobInProductionWith(UUID creator) {
        UUID jobId = submitOneJob(true);
        engine.transition(jobId, JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        engine.transition(TransitionCommand.of(jobId, JobAction.INVITE, ops, "invite").withCreator(creator));
        engine.transition(jobId, JobAction.ACCEPT, Actor.creator(creator), null);
        return jobId;
    }

    private List<String> actionsOf(UUID jobId) {
        return jdbc.sql("SELECT action FROM job_events WHERE job_id = ? ORDER BY job_version")
                .param(jobId)
                .query(String.class)
                .list();
    }

    /** Every job_events row has a matching outbox envelope with the same id, version and producer. */
    private void assertOutboxMirrorsEvents(UUID jobId) {
        List<Map<String, Object>> mismatches = jdbc.sql("""
                SELECT e.event_id FROM job_events e
                LEFT JOIN outbox o ON o.id = e.event_id
                WHERE e.job_id = ?
                  AND (o.id IS NULL OR o.topic <> 'core.job.v1'
                       OR (o.envelope->>'aggregateVersion')::bigint <> e.job_version
                       OR o.envelope->>'producer' <> 'core-service'
                       OR o.envelope->>'eventId' <> e.event_id::text)
                """).param(jobId).query().listOfRows();
        assertThat(mismatches).isEmpty();
    }
}
