package com.loofie.core.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class TransitionTableTest {

    @Test
    void terminalStatesHaveNoWayOut() {
        for (JobStatus s : JobStatus.values()) {
            if (s.isTerminal()) {
                // Only the record-only reason-code correction, which leaves the job where it is.
                assertThat(TransitionTable.from(s)).as("transitions out of %s", s)
                        .allSatisfy(r -> {
                            assertThat(r.to()).isEqualTo(s);
                            assertThat(r.action()).isEqualTo(JobAction.OPS_CORRECT_REASON);
                        });
            }
        }
    }

    @Test
    void everyNonTerminalStateHasAWayOut() {
        for (JobStatus s : JobStatus.values()) {
            if (!s.isTerminal()) {
                assertThat(TransitionTable.from(s)).as("transitions out of %s", s).isNotEmpty();
            }
        }
    }

    @Test
    void everyStoredStateIsReachable() {
        Set<JobStatus> reachable = TransitionTable.all().stream().map(TransitionRule::to)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(JobStatus.class)));
        for (JobStatus s : JobStatus.values()) {
            if (s.isStorable()) {
                assertThat(reachable).as("%s reachable", s).contains(s);
            }
        }
    }

    @Test
    void everyActionIsUsedAndHasRoles() {
        for (JobAction a : JobAction.values()) {
            assertThat(TransitionTable.rolesFor(a)).as("roles for %s", a).isNotEmpty();
        }
    }

    @Test
    void timerAndFinanceDrivenActionsAreSystemOnly() {
        for (JobAction a : EnumSet.of(JobAction.PAYMENT_CONFIRMED, JobAction.ACCEPT_TIMEOUT, JobAction.AUTO_APPROVE,
                JobAction.AUTO_START_PRODUCTION, JobAction.PRODUCTION_EXPIRED, JobAction.REPLACEMENT_EXPIRED,
                JobAction.PAYOUT_QUEUED, JobAction.PAYOUT_COMPLETED, JobAction.PREVIEW_READY)) {
            assertThat(TransitionTable.rolesFor(a)).as("roles for %s", a).containsExactly(ActorRole.SYSTEM);
        }
    }

    @Test
    void followUpsResolveToARule() {
        for (TransitionRule r : TransitionTable.all()) {
            if (r.followUp() != null) {
                assertThat(TransitionTable.find(r.to(), r.followUp())).as("follow-up of %s", r).isPresent();
            }
        }
    }
}
