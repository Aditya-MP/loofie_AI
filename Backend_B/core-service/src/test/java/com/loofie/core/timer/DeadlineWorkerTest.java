package com.loofie.core.timer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

/**
 * The timer worker acts only on what the database says is due. Other tests' jobs share the schema,
 * so assertions look at specific jobs, never at totals.
 */
@IntegrationTest
class DeadlineWorkerTest {

    @Autowired DeadlineWorker worker;
    @Autowired JobTransitionService engine;
    @Autowired JobFixtures fixtures;
    @Autowired JobRepository jobs;
    @Autowired AssignmentRepository assignments;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    final Actor ops = Actor.ops(UUID.randomUUID());

    @BeforeEach
    void startClock() {
        clock.set(Instant.parse("2026-11-16T04:30:00Z"));
    }

    @Test
    void brandSilentOnADraftIsRemindedThenAutoApproved() {
        Seeded seeded = fixtures.jobIn(JobStatus.DRAFT_SUBMITTED);
        UUID jobId = seeded.jobId();
        engine.transition(jobId, JobAction.PREVIEW_READY, Actor.system(), null);
        Instant reviewDue = job(jobId).getReviewDueAt();

        drain();
        assertThat(status(jobId)).isEqualTo(JobStatus.IN_REVIEW);
        assertThat(notices(jobId)).isEmpty();

        clock.set(reviewDue.minus(Duration.ofHours(48)));
        drain();
        drain(); // a second run must not send it again
        assertThat(notices(jobId)).containsExactly("REVIEW_DUE_48H");
        assertThat(noticeEvents(jobId)).isEqualTo(1);
        assertThat(lastNoticePayload(jobId, "audience")).contains("BRAND");

        clock.set(reviewDue.minus(Duration.ofHours(24)));
        drain();
        assertThat(notices(jobId)).containsExactly("REVIEW_DUE_48H", "REVIEW_DUE_24H");

        clock.set(reviewDue);
        drain();
        assertThat(status(jobId)).isEqualTo(JobStatus.APPROVED);
        assertThat(lastAction(jobId)).isEqualTo("AUTO_APPROVE");
        assertThat(lastActorType(jobId)).isEqualTo("SYSTEM");
    }

    @Test
    void creatorWhoNeverAnswersIsDeclinedAfter48Hours() {
        UUID creator = UUID.randomUUID();
        Seeded seeded = fixtures.jobIn(JobStatus.PAID);
        engine.transition(TransitionCommand.of(seeded.jobId(), JobAction.INVITE, ops, "invite").withCreator(creator));

        clock.advance(JobDeadlines.ACCEPT_WINDOW.minusMinutes(1));
        drain();
        assertThat(status(seeded.jobId())).isEqualTo(JobStatus.CREATOR_INVITED);

        clock.advance(Duration.ofMinutes(1));
        drain();
        assertThat(status(seeded.jobId())).isEqualTo(JobStatus.DECLINED);
        assertThat(assignments.findByJobIdAndStatus(seeded.jobId(), Assignment.Status.ACTIVE)).isEmpty();
    }

    @Test
    void brandIsRemindedToShipAndEveryoneIsToldWhenItIsLate() {
        Seeded seeded = fixtures.jobIn(JobStatus.CREATOR_INVITED);
        engine.transition(seeded.jobId(), JobAction.ACCEPT, Actor.creator(seeded.creatorId()), null);
        Job accepted = job(seeded.jobId());

        clock.set(accepted.getShipReminderAt());
        drain();
        assertThat(notices(seeded.jobId())).containsExactly("SHIP_REMINDER");

        clock.set(accepted.getShipDueAt());
        drain();
        assertThat(notices(seeded.jobId())).containsExactly("SHIP_REMINDER", "SHIP_LATE");
        assertThat(lastNoticePayload(seeded.jobId(), "audience")).contains("CREATOR", "OPS");
        assertThat(status(seeded.jobId())).isEqualTo(JobStatus.CREATOR_ACCEPTED); // a notice never moves the job
    }

    @Test
    void withoutCourierProofTheClockNeverStartsAndOpsIsAlerted() {
        UUID jobId = shipped();

        clock.advance(Duration.ofDays(10));
        drain();

        assertThat(status(jobId)).isEqualTo(JobStatus.PRODUCT_SHIPPED);
        assertThat(notices(jobId)).containsExactly("RECEIPT_PROMPT", "RECEIPT_OPS_CHECK");
    }

    @Test
    void clockStartsByItself24HoursAfterCourierDelivered() {
        UUID jobId = shipped();
        engine.transition(jobId, JobAction.COURIER_DELIVERED, ops, "tracking shows Delivered");

        clock.advance(Duration.ofHours(23));
        drain();
        assertThat(status(jobId)).isEqualTo(JobStatus.PRODUCT_SHIPPED);

        clock.advance(Duration.ofHours(1));
        drain();
        assertThat(status(jobId)).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(job(jobId).getProductionDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.PRODUCTION_WINDOW));
    }

    @Test
    void extensionMovesTheReminderAndTheExpiry() {
        Seeded seeded = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED, true, false);
        UUID jobId = seeded.jobId();
        engine.transition(jobId, JobAction.START_DIGITAL, Actor.system(), null);
        Instant firstDue = job(jobId).getProductionDueAt();

        clock.set(firstDue.minus(Duration.ofHours(48)));
        drain();
        assertThat(notices(jobId)).containsExactly("PRODUCTION_DUE_48H");

        engine.transition(jobId, JobAction.REQUEST_EXTENSION, Actor.creator(seeded.creatorId()), null);
        Instant newDue = job(jobId).getProductionDueAt();
        assertThat(newDue).isEqualTo(firstDue.plus(JobDeadlines.EXTENSION));

        clock.set(firstDue); // the old deadline passes: nothing happens, the extension holds
        drain();
        assertThat(status(jobId)).isEqualTo(JobStatus.IN_PRODUCTION);
        // The reminder is re-sent for the new deadline; the old 24h reminder no longer applies.
        assertThat(notices(jobId)).containsExactly("PRODUCTION_DUE_48H", "PRODUCTION_DUE_48H");

        clock.set(newDue);
        drain();
        assertThat(status(jobId)).isEqualTo(JobStatus.REPLACEMENT_NEEDED);
        assertThat(lastAction(jobId)).isEqualTo("PRODUCTION_EXPIRED");
    }

    @Test
    void noReplacementWithinTenDaysRefundsTheSlot() {
        Seeded seeded = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED, true, false);
        engine.transition(seeded.jobId(), JobAction.START_DIGITAL, Actor.system(), null);
        clock.set(job(seeded.jobId()).getProductionDueAt());
        drain();
        assertThat(status(seeded.jobId())).isEqualTo(JobStatus.REPLACEMENT_NEEDED);

        clock.advance(JobDeadlines.REPLACEMENT_WINDOW);
        drain();
        assertThat(status(seeded.jobId())).isEqualTo(JobStatus.REFUNDED);
    }

    @Test
    void timerActionsAreRefusedBeforeTheirDeadline() {
        Seeded seeded = fixtures.jobIn(JobStatus.DRAFT_SUBMITTED);
        engine.transition(seeded.jobId(), JobAction.PREVIEW_READY, Actor.system(), null);

        assertThatThrownBy(() -> engine.transition(seeded.jobId(), JobAction.AUTO_APPROVE, Actor.system(), null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("review window");
    }

    /** A job that can never move (bad data) must not be retried every run or starve the others. */
    @Test
    void aJobThatCannotMoveBacksOffInsteadOfBlockingOthers() {
        UUID broken = shipped();
        // auto_start_at says "due", but there is no courier proof, so the engine refuses: a data problem.
        fixtures.setDeadline(broken, "auto_start_at", clock.instant().minus(Duration.ofMinutes(1)));
        Seeded healthy = fixtures.jobIn(JobStatus.CREATOR_INVITED);
        fixtures.setDeadline(healthy.jobId(), "accept_due_at", clock.instant().minus(Duration.ofMinutes(1)));

        double refusedBefore = refused();
        drain();
        drain();

        assertThat(status(broken)).isEqualTo(JobStatus.PRODUCT_SHIPPED);
        assertThat(worker.isBackingOff(broken)).isTrue();
        assertThat(refused() - refusedBefore).as("tried once, then left alone").isEqualTo(1.0);
        assertThat(status(healthy.jobId())).isEqualTo(JobStatus.DECLINED);

        clock.advance(DeadlineWorker.FAILURE_BACKOFF.plusSeconds(1));
        drain();
        assertThat(refused() - refusedBefore).as("retried after the backoff").isEqualTo(2.0);
    }

    @Test
    void theWorkerReportsItsLagAndLastRun() {
        worker.runOnce();
        assertThat(meters.get("loofie.timer.last.run.age.seconds").gauge().value()).isZero();
        clock.advance(Duration.ofMinutes(3));
        assertThat(meters.get("loofie.timer.last.run.age.seconds").gauge().value()).isEqualTo(180.0);
    }

    // ---- helpers ----

    private double refused() {
        var counter = meters.find("loofie.timer.actions").tags("action", "AUTO_START_PRODUCTION", "outcome", "refused")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** Runs the worker until nothing more is due, like the scheduler does. */
    private void drain() {
        for (int i = 0; i < 20 && worker.runOnce().didWork(); i++) {
            // keep going
        }
    }

    private UUID shipped() {
        Seeded seeded = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        engine.transition(TransitionCommand.of(seeded.jobId(), JobAction.MARK_SHIPPED,
                        Actor.brandAdmin(UUID.randomUUID(), seeded.brandOrgId()), null)
                .with(TransitionDetails.shipment("Delhivery", "DL555")));
        return seeded.jobId();
    }

    private Job job(UUID jobId) {
        return jobs.findById(jobId).orElseThrow();
    }

    private JobStatus status(UUID jobId) {
        return job(jobId).getStatus();
    }

    private List<String> notices(UUID jobId) {
        return jdbc.sql("SELECT kind FROM deadline_notices WHERE job_id = ? ORDER BY sent_at, due_at")
                .param(jobId).query(String.class).list();
    }

    private long noticeEvents(UUID jobId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE message_key = ? AND event_type = 'JobDeadlineNotice'")
                .param(jobId.toString()).query(Long.class).single();
    }

    private String lastNoticePayload(UUID jobId, String field) {
        return jdbc.sql("""
                SELECT envelope -> 'payload' ->> ? FROM outbox
                WHERE message_key = ? AND event_type = 'JobDeadlineNotice' ORDER BY created_at DESC, id DESC LIMIT 1
                """)
                .params(field, jobId.toString()).query(String.class).single();
    }

    private String lastAction(UUID jobId) {
        return jdbc.sql("SELECT action FROM job_events WHERE job_id = ? ORDER BY job_version DESC LIMIT 1")
                .param(jobId).query(String.class).single();
    }

    private String lastActorType(UUID jobId) {
        return jdbc.sql("SELECT actor_type FROM job_events WHERE job_id = ? ORDER BY job_version DESC LIMIT 1")
                .param(jobId).query(String.class).single();
    }
}
