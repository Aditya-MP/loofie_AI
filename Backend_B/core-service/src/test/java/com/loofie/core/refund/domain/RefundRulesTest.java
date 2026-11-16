package com.loofie.core.refund.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.refund.domain.ApprovalPolicy.Required;
import com.loofie.core.refund.domain.ApprovalPolicy.Step;

/** The money rules as pure functions (DECISIONS.md sections 4 and 6). No database. */
class RefundRulesTest {

    static final long FEE_5000 = 5_000_00L;

    // ---- amounts ----

    @Test
    void theDecisionsExampleAddsUp() {
        // DECISIONS.md section 4: Rs 5,000 creator, 15% fee, 18% GST -> the slot is Rs 6,785.
        assertThat(RefundAmounts.of(RefundCase.FULL, FEE_5000, 1500, 1800))
                .isEqualTo(new RefundAmounts(6_785_00L, 0, 0));
    }

    @Test
    void eachCaseGivesBackWhatTheTableSays() {
        assertThat(RefundAmounts.of(RefundCase.MINUS_FEE, FEE_5000, 1500, 1800))
                .as("minus the fee: 5,000 + 18%").isEqualTo(new RefundAmounts(5_900_00L, 0, 0));
        RefundAmounts late = RefundAmounts.of(RefundCase.LATE_SHIP, FEE_5000, 1500, 1800);
        assertThat(late).as("creator's amount in cash, the fee as credit")
                .isEqualTo(new RefundAmounts(5_900_00L, 885_00L, 0));
        assertThat(late.total()).as("the brand loses nothing overall").isEqualTo(6_785_00L);
        assertThat(RefundAmounts.of(RefundCase.KILL_FEE, FEE_5000, 1500, 1800))
                .as("no refund, 50% kill fee to the creator").isEqualTo(new RefundAmounts(0, 0, 2_500_00L));
    }

    @Test
    void oddPaiseRoundHalfUpAndNothingIsLost() {
        // Rs 3,333.33 creator: fee 499.9995 -> 500.00; (3,833.33) x 18% = 689.9994 -> 690.00 (rounded per part).
        RefundAmounts full = RefundAmounts.of(RefundCase.FULL, 3_333_33L, 1500, 1800);
        assertThat(full.brandCash()).isEqualTo(3_333_33L + 500_00L + 690_00L);
        assertThat(RefundAmounts.percent(1, 5000)).as("0.5 paisa rounds up").isEqualTo(1);
        assertThat(RefundAmounts.percent(1, 4999)).isZero();
    }

    // ---- which case ----

    @Test
    void theCaseFollowsTheStage() {
        assertThat(RefundCase.of(JobAction.CANCEL, JobStatus.PAID, null)).isEqualTo(RefundCase.FULL);
        assertThat(RefundCase.of(JobAction.CANCEL, JobStatus.CREATOR_INVITED, null)).isEqualTo(RefundCase.FULL);
        assertThat(RefundCase.of(JobAction.CANCEL, JobStatus.CREATOR_ACCEPTED, null)).isEqualTo(RefundCase.MINUS_FEE);
        for (JobStatus s : new JobStatus[] {JobStatus.IN_PRODUCTION, JobStatus.DRAFT_SUBMITTED, JobStatus.IN_REVIEW,
                JobStatus.REVISION_REQUESTED}) {
            assertThat(RefundCase.of(JobAction.CANCEL, s, null)).as(s.name()).isEqualTo(RefundCase.KILL_FEE);
        }
        assertThat(RefundCase.of(JobAction.RESOLVE_REFUND, JobStatus.DISPUTED, null)).isEqualTo(RefundCase.FULL);
        assertThat(RefundCase.of(JobAction.REPLACEMENT_EXPIRED, JobStatus.REPLACEMENT_NEEDED, "CREATOR_WITHDREW"))
                .as("Loofie's own failure: fee included").isEqualTo(RefundCase.FULL);
        assertThat(RefundCase.of(JobAction.RESOLVE_REFUND, JobStatus.REPLACEMENT_NEEDED, "BRAND_LATE_SHIP"))
                .isEqualTo(RefundCase.LATE_SHIP);
        assertThatThrownBy(() -> RefundCase.of(JobAction.CANCEL, JobStatus.PRODUCT_SHIPPED, null))
                .as("shipped-not-received waits for a decision").isInstanceOf(IllegalArgumentException.class);
    }

    // ---- who must approve ----

    @Test
    void theLimitsAreExactlyAtTheirEdges() {
        assertThat(ApprovalPolicy.required(10_000_00L, false, 0)).isEqualTo(new Required(true, false));
        assertThat(ApprovalPolicy.required(10_000_01L, false, 0)).isEqualTo(new Required(false, false));
        assertThat(ApprovalPolicy.required(1_00L, true, 0)).as("after acceptance: never self")
                .isEqualTo(new Required(false, false));
        assertThat(ApprovalPolicy.required(25_000_00L, true, 0).founderRequired()).isFalse();
        assertThat(ApprovalPolicy.required(25_000_01L, true, 0).founderRequired()).isTrue();
        assertThat(ApprovalPolicy.required(1_00L, false, 2).founderRequired()).isFalse();
        assertThat(ApprovalPolicy.required(1_00L, false, 3).founderRequired()).as("a 4th in 30 days").isTrue();
    }

    @Test
    void nobodyApprovesTheirOwnRefundOnceSomeoneLostWork() {
        UUID asker = UUID.randomUUID();
        Required afterAcceptance = new Required(false, false);
        assertThatThrownBy(() -> ApprovalPolicy.nextStep(afterAcceptance, asker, null, asker, ActorRole.OPS_LEAD))
                .isInstanceOf(RefundRuleException.class).hasMessageContaining("someone else");
        assertThatThrownBy(() -> ApprovalPolicy.nextStep(afterAcceptance, asker, null, UUID.randomUUID(), ActorRole.OPS))
                .as("plain ops never approve").isInstanceOf(RefundRuleException.class);
        assertThat(ApprovalPolicy.nextStep(afterAcceptance, asker, null, UUID.randomUUID(), ActorRole.OPS_LEAD))
                .isEqualTo(Step.LEAD);
        assertThat(ApprovalPolicy.nextStep(afterAcceptance, asker, null, UUID.randomUUID(), ActorRole.FOUNDER))
                .as("pilot: the founder stands in for a lead").isEqualTo(Step.LEAD);
        assertThat(ApprovalPolicy.nextStep(new Required(true, false), asker, null, asker, ActorRole.OPS_LEAD))
                .as("small, nobody accepted: one lead may do both").isEqualTo(Step.LEAD);
    }

    @Test
    void largeRefundsNeedALeadThenADifferentFounder() {
        UUID asker = UUID.randomUUID();
        UUID lead = UUID.randomUUID();
        UUID founder = UUID.randomUUID();
        Required big = new Required(false, true);
        assertThatThrownBy(() -> ApprovalPolicy.nextStep(big, asker, null, founder, ActorRole.FOUNDER))
                .hasMessageContaining("OPS_LEAD approves first");
        assertThat(ApprovalPolicy.nextStep(big, asker, null, lead, ActorRole.OPS_LEAD)).isEqualTo(Step.LEAD);
        assertThatThrownBy(() -> ApprovalPolicy.nextStep(big, asker, lead, UUID.randomUUID(), ActorRole.OPS_LEAD))
                .hasMessageContaining("founder");
        assertThatThrownBy(() -> ApprovalPolicy.nextStep(big, asker, lead, lead, ActorRole.FOUNDER))
                .as("one person cannot be both").hasMessageContaining("different person");
        assertThatThrownBy(() -> ApprovalPolicy.nextStep(big, founder, lead, founder, ActorRole.FOUNDER))
                .as("the founder asked: cannot approve it too").hasMessageContaining("different person");
        assertThat(ApprovalPolicy.nextStep(big, asker, lead, founder, ActorRole.FOUNDER)).isEqualTo(Step.FOUNDER);
        assertThatThrownBy(() -> ApprovalPolicy.nextStep(new Required(false, false), asker, lead, founder,
                ActorRole.FOUNDER)).hasMessageContaining("already approved");
    }
}
