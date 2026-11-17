package com.loofie.core.job.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.ReasonCode;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.messaging.OutboxAdmin;
import com.loofie.core.refund.app.RefundService;
import com.loofie.core.refund.domain.RefundReason;
import com.loofie.core.refund.domain.RefundRuleException;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the AI's track record learns from (docs/features/ai-events.md, A1-A5). */
@IntegrationTest
class AiEventsRound2Test {

    @Autowired JobTransitionService engine;
    @Autowired RefundService refunds;
    @Autowired OutboxAdmin outbox;
    @Autowired JobFixtures fixtures;
    @Autowired JobRepository jobs;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;

    final Actor ops = Actor.ops(UUID.randomUUID());
    final Actor lead = Actor.opsLead(UUID.randomUUID());

    // ---- A1 job_outcome ----

    @Test
    void aJobDeliveredOnTimeWithOneRevisionClosesWithThatOutcome() {
        Seeded job = inProduction(Duration.ofHours(10));
        Actor creator = Actor.creator(job.creatorId());
        engine.transition(job.jobId(), JobAction.SUBMIT_DRAFT, creator, null);
        review(job);
        engine.transition(job.jobId(), JobAction.REQUEST_REVISION, brand(job), null);
        clock.advance(Duration.ofDays(3)); // the resubmission is after the deadline: only the first draft counts
        engine.transition(job.jobId(), JobAction.RESUBMIT, creator, null);
        review(job);
        approveAndPay(job);

        JsonNode o = outcome(job);
        assertThat(o.get("onTime").asBoolean()).isTrue();
        assertThat(o.get("revisions").asInt()).isEqualTo(1);
        assertThat(o.get("disputed").asBoolean()).isFalse();
        assertThat(o.get("replaced").asBoolean()).isFalse();
        assertThat(o.get("creatorId").asString()).isEqualTo(job.creatorId().toString());
        assertThat(fixtures.status(job.jobId())).isEqualTo("CLOSED");
    }

    @Test
    void aFirstDraftAfterTheDeadlineIsLateButTheExtensionMovesTheDeadline() {
        Seeded late = inProduction(Duration.ofHours(1));
        clock.advance(Duration.ofHours(2));
        engine.transition(late.jobId(), JobAction.SUBMIT_DRAFT, Actor.creator(late.creatorId()), null);
        review(late);
        approveAndPay(late);
        assertThat(outcome(late).get("onTime").asBoolean()).isFalse();

        Seeded extended = inProduction(Duration.ofHours(1));
        engine.transition(extended.jobId(), JobAction.REQUEST_EXTENSION, Actor.creator(extended.creatorId()), null);
        clock.advance(Duration.ofHours(2)); // past the original deadline, inside the extended one
        engine.transition(extended.jobId(), JobAction.SUBMIT_DRAFT, Actor.creator(extended.creatorId()), null);
        review(extended);
        approveAndPay(extended);
        assertThat(outcome(extended).get("onTime").asBoolean()).isTrue();
    }

    // ---- A2 ghosting ----

    @Test
    void aCreatorWhoGhostsIsMarkedUnresponsiveAndReplaced() {
        Seeded job = inProduction(Duration.ofHours(1));
        clock.advance(Duration.ofHours(2));
        engine.transition(job.jobId(), JobAction.PRODUCTION_EXPIRED, Actor.system(), null);

        assertThat(lastCode(job)).isEqualTo("CREATOR_UNRESPONSIVE");
        JsonNode o = outcome(job);
        assertThat(o.get("replaced").asBoolean()).isTrue();
        assertThat(o.get("onTime").asBoolean()).isFalse();
        assertThat(o.get("reasonCode").asString()).isEqualTo("CREATOR_UNRESPONSIVE");
        assertThat(o.get("faultParty").asString()).isEqualTo("CREATOR");
    }

    @Test
    void aCreatorReplacedBecauseTheBrandShippedLateCarriesTheBrandsFault() {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        fixtures.setDeadline(job.jobId(), "ship_due_at", clock.instant().minus(Duration.ofMinutes(1)));
        engine.transition(job.jobId(), JobAction.CREATOR_WITHDRAW, Actor.creator(job.creatorId()), null);
        JsonNode o = outcome(job);
        assertThat(o.get("replaced").asBoolean()).isTrue();
        assertThat(o.get("faultParty").asString()).isEqualTo("BRAND");
    }

    // ---- A3 dispute decisions carry the fault ----

    @Test
    void replacingAfterADisputeNeedsTheFaultAndSendsIt() {
        Seeded job = fixtures.jobIn(JobStatus.DISPUTED);
        assertThatThrownBy(() -> engine.transition(job.jobId(), JobAction.RESOLVE_REPLACE, ops, "checked the video"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("reason code");
        engine.transition(TransitionCommand.of(job.jobId(), JobAction.RESOLVE_REPLACE, ops, "missed 2 must-say points")
                .with(TransitionDetails.reason(ReasonCode.OTHER)));
        assertThat(outcome(job).get("reasonCode").asString()).isEqualTo("OTHER");
    }

    @Test
    void refundingADisputeNeedsTheFaultAndTheApprovalAppliesIt() {
        Seeded job = fixtures.jobIn(JobStatus.DISPUTED);
        assertThatThrownBy(() -> refunds.request(ops, job.jobId(), JobAction.RESOLVE_REFUND, RefundReason.DISPUTE_UPHELD,
                "brand was right")).isInstanceOf(RefundRuleException.class).hasMessageContaining("whose fault");
        var r = refunds.request(ops, job.jobId(), JobAction.RESOLVE_REFUND, RefundReason.DISPUTE_UPHELD,
                "draft ignored the brief", ReasonCode.CREATOR_UNRESPONSIVE);
        refunds.approve(lead, r.id(), null);

        assertThat(fixtures.status(job.jobId())).isEqualTo("REFUNDED");
        assertThat(lastCode(job)).isEqualTo("CREATOR_UNRESPONSIVE");
        assertThat(outcome(job).get("replaced").asBoolean()).isTrue();
    }

    @Test
    void aCancellationSendsNoOutcome() {
        Seeded job = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        var r = refunds.request(ops, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "campaign stopped");
        refunds.approve(lead, r.id(), null);
        assertThat(outcomes(job)).as("the creator did not fail: no record either way").isZero();
    }

    // ---- A4 correction ----

    @Test
    void aWronglyAccusedCreatorIsClearedWithoutTouchingTheJob() {
        // The timer marks a silent creator unresponsive; ops later learns they were in hospital.
        Seeded job = inProduction(Duration.ofHours(1));
        clock.advance(Duration.ofHours(2));
        engine.transition(job.jobId(), JobAction.PRODUCTION_EXPIRED, Actor.system(), null);
        UUID released = job.creatorId();
        Instant replacementDue = jobs.findById(job.jobId()).orElseThrow().getReplacementDueAt();
        String releaseEvent = jdbc.sql("SELECT event_id::text FROM job_events WHERE job_id = ? AND action = 'PRODUCTION_EXPIRED'")
                .param(job.jobId()).query(String.class).single();

        clock.advance(Duration.ofHours(5));
        engine.transition(TransitionCommand.of(job.jobId(), JobAction.OPS_CORRECT_REASON, ops,
                "creator was in hospital, proof received").with(TransitionDetails.reason(ReasonCode.OTHER)));

        var after = jobs.findById(job.jobId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.REPLACEMENT_NEEDED);
        assertThat(after.getReplacementDueAt()).as("no clock moves").isEqualTo(replacementDue);
        JsonNode p = lastPayload(job);
        assertThat(p.get("reasonCode").asString()).isEqualTo("OTHER");
        assertThat(p.get("faultParty").asString()).isEqualTo("NONE");
        assertThat(p.get("creatorId").asString()).as("about the creator who was blamed").isEqualTo(released.toString());
        assertThat(p.get("corrects").asString()).isEqualTo(releaseEvent);
        assertThat(p.get("previousReasonCode").asString()).isEqualTo("CREATOR_UNRESPONSIVE");
        assertThat(p.get("fromState").asString()).isEqualTo(p.get("toState").asString());
    }

    @Test
    void aCorrectionNeverAccusesNeverRepeatsAndNeverResetsAClock() {
        Seeded none = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(none.jobId(), JobAction.OPS_CORRECT_REASON, ops,
                "x").with(TransitionDetails.reason(ReasonCode.OTHER)))).hasMessageContaining("no reason code to correct");

        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        fixtures.setDeadline(job.jobId(), "ship_due_at", clock.instant().minus(Duration.ofMinutes(1)));
        engine.transition(job.jobId(), JobAction.CREATOR_WITHDRAW, Actor.creator(job.creatorId()), null);
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(job.jobId(), JobAction.OPS_CORRECT_REASON, ops,
                "x").with(TransitionDetails.reason(ReasonCode.FALSE_CLAIM)))).hasMessageContaining("FALSE_CLAIM");
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(job.jobId(), JobAction.OPS_CORRECT_REASON, ops,
                "x").with(TransitionDetails.reason(ReasonCode.BRAND_LATE_SHIP)))).hasMessageContaining("already");
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(job.jobId(), JobAction.OPS_CORRECT_REASON,
                brand(job), null).with(TransitionDetails.reason(ReasonCode.OTHER))))
                .isInstanceOf(ActorNotAllowedException.class);

        // DECISIONS.md's own example: a FALSE_CLAIM later reversed. The creator is in production by then, and the
        // correction must not hand them a fresh 120 hours.
        Seeded claimed = fixtures.jobIn(JobStatus.DELIVERY_ISSUE);
        engine.transition(claimed.jobId(), JobAction.RESOLVE_FALSE_CLAIM, ops, "courier shows delivered with OTP");
        assertThat(lastCode(claimed)).isEqualTo("FALSE_CLAIM");
        clock.advance(Duration.ofHours(7));
        Instant due = jobs.findById(claimed.jobId()).orElseThrow().getProductionDueAt();
        engine.transition(TransitionCommand.of(claimed.jobId(), JobAction.OPS_CORRECT_REASON, ops,
                "OTP was the neighbour's; parcel really lost").with(TransitionDetails.reason(ReasonCode.COURIER_LOST)));
        var after = jobs.findById(claimed.jobId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(after.getProductionDueAt()).isEqualTo(due);
        assertThat(lastCode(claimed)).isEqualTo("COURIER_LOST");
    }

    // ---- A5 parked events ----

    @Test
    void anOpsLeadCanResendARefusedEvent() {
        UUID row = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at, parked_at, attempts, last_error)
                VALUES (?, 'core.job.v1', 'parked-test', 'JobStateChanged', '{}'::jsonb, now(), now(), 1, 'Rejected: HTTP 422')
                """).param(row).update();

        assertThat(outbox.parked(ops, 100)).anySatisfy(p -> {
            assertThat(p.id()).isEqualTo(row);
            assertThat(p.lastError()).contains("422");
        });
        assertThatThrownBy(() -> outbox.requeue(ops, row)).isInstanceOf(ActorNotAllowedException.class);
        assertThat(outbox.requeue(lead, row)).isTrue();
        assertThat(outbox.requeue(lead, row)).as("not parked any more").isFalse();
        var r = jdbc.sql("SELECT parked_at, attempts FROM outbox WHERE id = ?").param(row).query().singleRow();
        assertThat(r.get("parked_at")).isNull();
        assertThat(r.get("attempts")).isEqualTo(0);
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ?").param(row).update();
    }

    // ---- helpers ----

    private Seeded inProduction(Duration left) {
        Seeded job = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        fixtures.setDeadline(job.jobId(), "production_due_at", clock.instant().plus(left));
        return job;
    }

    private Actor brand(Seeded job) {
        return Actor.brandAdmin(UUID.randomUUID(), job.brandOrgId());
    }

    private void review(Seeded job) {
        engine.transition(job.jobId(), JobAction.PREVIEW_READY, Actor.system(), null);
    }

    private void approveAndPay(Seeded job) {
        engine.transition(job.jobId(), JobAction.APPROVE, brand(job), null);
        engine.transition(job.jobId(), JobAction.PAYOUT_QUEUED, Actor.system(), null);
        engine.transition(job.jobId(), JobAction.PAYOUT_COMPLETED, Actor.system(), null);
    }

    private JsonNode outcome(Seeded job) {
        List<String> rows = jdbc.sql("""
                SELECT envelope::text FROM outbox WHERE message_key = ? AND event_type = 'JobOutcome' ORDER BY seq
                """).param(job.jobId().toString()).query(String.class).list();
        assertThat(rows).as("one outcome").hasSize(1);
        return json.readTree(rows.getFirst()).get("payload");
    }

    private long outcomes(Seeded job) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE message_key = ? AND event_type = 'JobOutcome'")
                .param(job.jobId().toString()).query(Long.class).single();
    }

    private String lastCode(Seeded job) {
        return jdbc.sql("""
                SELECT reason_code FROM job_events WHERE job_id = ? AND reason_code IS NOT NULL
                ORDER BY occurred_at DESC, job_version DESC NULLS LAST LIMIT 1
                """).param(job.jobId()).query(String.class).single();
    }

    private JsonNode lastPayload(Seeded job) {
        String p = jdbc.sql("""
                SELECT payload::text FROM job_events WHERE job_id = ? ORDER BY occurred_at DESC, job_version DESC NULLS LAST LIMIT 1
                """).param(job.jobId()).query(String.class).single();
        return json.readTree(p);
    }
}
