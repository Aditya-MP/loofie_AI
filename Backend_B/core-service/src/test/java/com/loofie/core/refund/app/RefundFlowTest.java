package com.loofie.core.refund.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.timer.DeadlineWorker;
import com.loofie.core.refund.app.RefundService.RefundView;
import com.loofie.core.refund.domain.RefundCase;
import com.loofie.core.refund.domain.RefundReason;
import com.loofie.core.refund.domain.RefundRuleException;
import com.loofie.core.refund.infra.RefundRequestRepository.Status;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

/** Request, then approve: money moves only after approval (DECISIONS.md section 6; f5-job-engine.md Round 2). */
@IntegrationTest
class RefundFlowTest {

    @Autowired RefundService refunds;
    @Autowired RefundSummary summary;
    @Autowired JobTransitionService engine;
    @Autowired JobFixtures fixtures;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;
    @Autowired DeadlineWorker timer;

    final Actor ops = Actor.ops(UUID.randomUUID());
    final Actor lead = Actor.opsLead(UUID.randomUUID());
    final Actor otherLead = Actor.opsLead(UUID.randomUUID());
    final Actor founder = Actor.founder(UUID.randomUUID());

    @Test
    void beforeAnyoneAcceptedOneLeadMayAskAndApproveASmallFullRefund() {
        Seeded job = fixtures.jobIn(JobStatus.PAID); // Rs 5,000 creator fee, nobody accepted
        RefundView r = refunds.request(lead, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "brand withdrew");

        assertThat(r.refundCase()).isEqualTo(RefundCase.FULL);
        assertThat(r.brandCashPaise()).isEqualTo(6_785_00L);
        assertThat(r.leadMaySelfApprove()).isTrue();
        assertThat(status(job)).as("nothing moved yet").isEqualTo("PAID");

        RefundView done = refunds.approve(lead, r.id(), null);

        assertThat(done.status()).isEqualTo(Status.APPROVED);
        assertThat(status(job)).isEqualTo("CANCELLED");
        assertThat(held(job)).isFalse();
        assertThat(inbox("BRAND_ORG", job.brandOrgId(), "REFUND_APPROVED")).isEqualTo(1);
    }

    @Test
    void afterAcceptanceTheAskerCannotApproveAndPlainOpsNeverCan() {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        markAccepted(job);
        RefundView r = refunds.request(lead, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "brand withdrew");
        assertThat(r.refundCase()).isEqualTo(RefundCase.MINUS_FEE);
        assertThat(r.brandCashPaise()).isEqualTo(5_900_00L);

        assertThatThrownBy(() -> refunds.approve(lead, r.id(), null)).isInstanceOf(RefundRuleException.class)
                .hasMessageContaining("someone else");
        assertThatThrownBy(() -> refunds.approve(ops, r.id(), null)).isInstanceOf(RefundRuleException.class);
        assertThat(status(job)).isEqualTo("CREATOR_ACCEPTED");

        refunds.approve(otherLead, r.id(), null);
        assertThat(status(job)).isEqualTo("CANCELLED");
    }

    @Test
    void anOpenRequestHoldsTheJobUntilItIsDecided() {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        RefundView r = refunds.request(ops, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "brand withdrew");
        Actor brand = Actor.brandAdmin(UUID.randomUUID(), job.brandOrgId());

        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(job.jobId(), JobAction.MARK_SHIPPED, brand, null)
                .with(TransitionDetails.shipVia("DELHIVERY", JobFixtures.trackingNumber()))))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("refund request is open");
        assertThatThrownBy(() -> refunds.request(ops, job.jobId(), JobAction.CANCEL, RefundReason.OTHER, "again"))
                .as("one open request per job").isInstanceOf(TransitionGuardException.class);

        refunds.withdraw(ops, r.id());
        assertThat(held(job)).isFalse();
        engine.transition(TransitionCommand.of(job.jobId(), JobAction.MARK_SHIPPED, brand, null)
                .with(TransitionDetails.shipVia("DELHIVERY", JobFixtures.trackingNumber())));
        assertThat(status(job)).isEqualTo("PRODUCT_SHIPPED");
    }

    @Test
    void aRejectionNeedsALeadAndAReasonAndReleasesTheJob() {
        Seeded job = fixtures.jobIn(JobStatus.PAID);
        RefundView r = refunds.request(ops, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "brand asked");
        assertThatThrownBy(() -> refunds.reject(ops, r.id(), "no")).isInstanceOf(RefundRuleException.class);
        assertThatThrownBy(() -> refunds.reject(lead, r.id(), " ")).isInstanceOf(RefundRuleException.class);
        assertThat(refunds.reject(lead, r.id(), "brand confirmed it wants to continue").status())
                .isEqualTo(Status.REJECTED);
        assertThat(held(job)).isFalse();
        assertThat(status(job)).isEqualTo("PAID");
        assertThatThrownBy(() -> refunds.approve(otherLead, r.id(), null)).hasMessageContaining("already REJECTED");
    }

    @Test
    void aLargeRefundNeedsALeadAndThenTheFounder() {
        Seeded job = fixtures.jobIn(JobStatus.PAID);
        jdbc.sql("UPDATE jobs SET creator_fee_paise = 30000_00 WHERE id = ?").param(job.jobId()).update();
        RefundView r = refunds.request(ops, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "brand withdrew");
        assertThat(r.founderRequired()).isTrue();
        assertThat(inbox("FOUNDER", null, "REFUND_NEEDS_FOUNDER")).isPositive();

        assertThatThrownBy(() -> refunds.approve(founder, r.id(), null)).hasMessageContaining("OPS_LEAD approves first");
        RefundView half = refunds.approve(lead, r.id(), null);
        assertThat(half.status()).isEqualTo(Status.REQUESTED);
        assertThat(half.waitingFor()).isEqualTo("FOUNDER");
        assertThat(status(job)).as("one approval is not enough").isEqualTo("PAID");

        refunds.approve(founder, r.id(), "ok");
        assertThat(status(job)).isEqualTo("CANCELLED");
    }

    @Test
    void aFourthRefundToOneBrandInThirtyDaysNeedsTheFounder() {
        UUID brand = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            Seeded job = jobForBrand(brand);
            RefundView r = refunds.request(lead, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "cancel " + i);
            assertThat(r.founderRequired()).isFalse();
            refunds.approve(lead, r.id(), null);
        }
        RefundView fourth = refunds.request(lead, jobForBrand(brand).jobId(), JobAction.CANCEL,
                RefundReason.BRAND_CANCELLED, "cancel 4");
        assertThat(fourth.founderRequired()).isTrue();
        assertThat(refunds.detail(lead, fourth.id()).brandHistory()).hasSize(3);
        assertThat(refunds.detail(lead, fourth.id()).approvedToBrandLast30Days()).isEqualTo(3);
    }

    @Test
    void cancellingAfterTheShootStartedPaysTheCreatorAKillFeeAndRefundsNothing() {
        Seeded job = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        markAccepted(job);
        RefundView r = refunds.request(ops, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "brand stopped campaign");
        assertThat(r.refundCase()).isEqualTo(RefundCase.KILL_FEE);
        assertThat(r.brandCashPaise()).isZero();
        assertThat(r.creatorKillFeePaise()).isEqualTo(2_500_00L);
        refunds.approve(lead, r.id(), null);
        assertThat(status(job)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT count(*) FROM assignments WHERE job_id = ? AND status = 'ACTIVE'")
                .param(job.jobId()).query(Long.class).single()).isZero();
    }

    @Test
    void aLateShipperGetsTheCreatorsAmountInCashAndTheFeeAsCredit() {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        markAccepted(job);
        fixtures.setDeadline(job.jobId(), "ship_due_at", clock.instant().minus(Duration.ofHours(1)));
        engine.transition(job.jobId(), JobAction.CREATOR_WITHDRAW, Actor.creator(job.creatorId()), null);

        RefundView r = refunds.request(ops, job.jobId(), JobAction.RESOLVE_REFUND, RefundReason.BRAND_LATE_SHIP,
                "brand wants the money back, not a replacement");
        assertThat(r.refundCase()).isEqualTo(RefundCase.LATE_SHIP);
        assertThat(r.brandCashPaise()).isEqualTo(5_900_00L);
        assertThat(r.brandCreditPaise()).isEqualTo(885_00L);
        refunds.approve(lead, r.id(), null);
        assertThat(status(job)).isEqualTo("REFUNDED");
    }

    @Test
    void aDecidedRequestCanNeverBeChangedOrDeleted() {
        Seeded job = fixtures.jobIn(JobStatus.PAID);
        RefundView r = refunds.request(lead, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "x");
        refunds.approve(lead, r.id(), null);
        assertThatThrownBy(() -> jdbc.sql("UPDATE refund_requests SET brand_cash_paise = 1 WHERE id = ?").param(r.id())
                .update()).rootCause().hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM refund_requests WHERE id = ?").param(r.id()).update())
                .rootCause().hasMessageContaining("cannot be changed");
    }

    @Test
    void moneyNeverMovesThroughThePlainActionButAnUnpaidOrderCancelsDirectly() {
        Seeded paid = fixtures.jobIn(JobStatus.DISPUTED);
        assertThatThrownBy(() -> engine.transition(paid.jobId(), JobAction.RESOLVE_REFUND, lead, "upheld"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("refund request");
        Seeded unpaid = fixtures.jobIn(JobStatus.AWAITING_PAYMENT);
        assertThatThrownBy(() -> refunds.request(ops, unpaid.jobId(), JobAction.CANCEL, RefundReason.OTHER, "x"))
                .hasMessageContaining("moves no money");
        engine.transition(unpaid.jobId(), JobAction.CANCEL, ops, "never paid");
        assertThat(status(unpaid)).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> refunds.request(Actor.brandAdmin(UUID.randomUUID(), paid.brandOrgId()), paid.jobId(),
                JobAction.RESOLVE_REFUND, RefundReason.OTHER, "me")).isInstanceOf(ActorNotAllowedException.class);
    }

    @Test
    void theTimerAsksForTheRefundInsteadOfRefunding() {
        Seeded job = fixtures.jobIn(JobStatus.REPLACEMENT_NEEDED);
        fixtures.setDeadline(job.jobId(), "replacement_due_at", clock.instant().minus(Duration.ofMinutes(1)));
        timer.runOnce();
        assertThat(status(job)).isEqualTo("REPLACEMENT_NEEDED");
        assertThat(held(job)).isTrue();
        var open = jdbc.sql("SELECT id, reason_code, requested_by_role, refund_case FROM refund_requests WHERE job_id = ?")
                .param(job.jobId()).query().singleRow();
        assertThat(open.get("reason_code")).isEqualTo("LOOFIE_NO_CREATOR");
        assertThat(open.get("requested_by_role")).isEqualTo("SYSTEM");
        assertThat(open.get("refund_case")).as("Loofie failed: fee included").isEqualTo("FULL");
        timer.runOnce(); // held: not due again, no second request
        assertThat(jdbc.sql("SELECT count(*) FROM refund_requests WHERE job_id = ?").param(job.jobId())
                .query(Long.class).single()).isEqualTo(1);
        refunds.approve(lead, (UUID) open.get("id"), null);
        assertThat(status(job)).isEqualTo("REFUNDED");
    }

    @Test
    void twoLeadsApprovingAtTheSameMomentMoveTheMoneyOnce() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        markAccepted(job);
        RefundView r = refunds.request(ops, job.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "brand withdrew");
        List<Actor> leads = List.of(lead, otherLead, Actor.opsLead(UUID.randomUUID()), Actor.opsLead(UUID.randomUUID()));
        var start = new CountDownLatch(1);
        var ok = new AtomicInteger();
        var refused = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(leads.size())) {
            List<Future<?>> runs = new ArrayList<>();
            for (Actor l : leads) {
                runs.add(pool.submit(() -> {
                    start.await();
                    try {
                        refunds.approve(l, r.id(), null);
                        ok.incrementAndGet();
                    } catch (RefundRuleException e) {
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : runs) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(ok.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(leads.size() - 1);
        assertThat(jdbc.sql("SELECT count(*) FROM job_events WHERE job_id = ? AND action = 'CANCEL'").param(job.jobId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theFounderGetsADailySummary() {
        Seeded a = fixtures.jobIn(JobStatus.PAID);
        Seeded b = fixtures.jobIn(JobStatus.PAID);
        RefundView ra = refunds.request(ops, a.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "x");
        RefundView rb = refunds.request(ops, b.jobId(), JobAction.CANCEL, RefundReason.BRAND_CANCELLED, "y");
        refunds.approve(lead, ra.id(), null);
        refunds.reject(lead, rb.id(), "no");
        var today = clock.instant().atZone(com.loofie.core.calendar.domain.WorkingDays.INDIA).toLocalDate();

        RefundSummary.Day d = summary.day(founder, today);
        assertThat(d.approved()).isGreaterThanOrEqualTo(1);
        assertThat(d.rejected()).isGreaterThanOrEqualTo(1);
        assertThat(d.lines()).anySatisfy(l -> assertThat(l.requestId()).isEqualTo(ra.id()));
        assertThatThrownBy(() -> summary.day(ops, today)).isInstanceOf(RefundRuleException.class);

        clock.advance(Duration.ofDays(1));
        summary.sendYesterday();
        assertThat(inbox("FOUNDER", null, "REFUND_DAILY_SUMMARY")).isPositive();
    }

    // ---- helpers ----

    private Seeded jobForBrand(UUID brand) {
        Seeded job = fixtures.jobIn(JobStatus.PAID);
        jdbc.sql("UPDATE jobs SET brand_org_id = ? WHERE id = ?").params(brand, job.jobId()).update();
        jdbc.sql("UPDATE orders SET brand_org_id = ? WHERE id = ?").params(brand, job.orderId()).update();
        return job;
    }

    /** The fixture puts a job straight into a state; this records the acceptance that got it there. */
    private void markAccepted(Seeded job) {
        jdbc.sql("""
                INSERT INTO job_events (event_id, event_type, order_id, job_id, job_version, actor_type, action,
                                        from_state, to_state, payload, occurred_at)
                VALUES (?, 'JobStateChanged', ?, ?, 0, 'CREATOR', 'ACCEPT', 'CREATOR_INVITED', 'CREATOR_ACCEPTED',
                        '{}'::jsonb, now())
                """).params(UUID.randomUUID(), job.orderId(), job.jobId()).update();
    }

    private String status(Seeded job) {
        return fixtures.status(job.jobId());
    }

    private boolean held(Seeded job) {
        return jdbc.sql("SELECT refund_hold FROM jobs WHERE id = ?").param(job.jobId()).query(Boolean.class).single();
    }

    private long inbox(String type, UUID id, String kind) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_type = ? AND recipient_id IS NOT DISTINCT FROM CAST(? AS uuid)"
                        + " AND kind = ?").params(type, id, kind).query(Long.class).single();
    }
}
