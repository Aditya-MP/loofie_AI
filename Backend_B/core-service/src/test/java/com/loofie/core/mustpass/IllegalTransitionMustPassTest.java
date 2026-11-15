package com.loofie.core.mustpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.TransitionTable;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;

/**
 * Spec section 5 must-pass test: "An illegal state change is rejected".
 *
 * <p>For every stored state and every action not in the transition table from that state, calls
 * the real entry point with an actor whose role is allowed to perform that action somewhere, so
 * the only reason to fail is the state. Asserts the call is rejected and nothing is written.
 */
@IntegrationTest
class IllegalTransitionMustPassTest {

    @Autowired
    JobTransitionService engine;

    @Autowired
    JobFixtures fixtures;

    @ParameterizedTest(name = "every illegal action from {0} is rejected")
    @EnumSource(value = JobStatus.class, names = "DRAFT_BRIEF", mode = EnumSource.Mode.EXCLUDE)
    void everyIllegalActionIsRejected(JobStatus state) {
        Seeded job = fixtures.jobIn(state);
        int attempted = 0;

        for (JobAction action : JobAction.values()) {
            if (TransitionTable.find(state, action).isPresent()) {
                continue;
            }
            Actor actor = actorAllowedFor(action, job);
            // In states with no creator on the job, a creator is outside the job's scope: they get the
            // "not found" answer before the state is even looked at. Both mean "rejected, nothing written".
            Class<? extends RuntimeException> expected = actor.role() == ActorRole.CREATOR && !JobFixtures.hasActiveCreator(state)
                    ? JobNotFoundException.class : IllegalTransitionException.class;
            assertThatThrownBy(() -> engine.transition(job.jobId(), action, actor, "test"))
                    .as("%s from %s", action, state)
                    .isInstanceOf(expected);
            attempted++;
        }

        assertThat(attempted).isPositive();
        assertThat(fixtures.status(job.jobId())).isEqualTo(state.name());
        assertThat(fixtures.eventCount(job.jobId())).isZero();
        assertThat(fixtures.outboxCount(job.jobId())).isZero();
    }

    @Test
    void paidCannotJumpToApproved() {
        Seeded job = fixtures.jobIn(JobStatus.PAID);
        Actor brand = Actor.brandAdmin(UUID.randomUUID(), job.brandOrgId());

        assertThatThrownBy(() -> engine.transition(job.jobId(), JobAction.APPROVE, brand, null))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("APPROVE is not allowed from PAID");
        assertThatThrownBy(() -> engine.transition(job.jobId(), JobAction.AUTO_APPROVE, Actor.system(), null))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(fixtures.status(job.jobId())).isEqualTo("PAID");
    }

    @Test
    void databaseRejectsStatesOutsideTheMachine() {
        assertThatThrownBy(() -> fixtures.jobIn(JobStatus.DRAFT_BRIEF))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Actor actorAllowedFor(JobAction action, Seeded job) {
        ActorRole role = TransitionTable.rolesFor(action).iterator().next();
        return switch (role) {
            case SYSTEM -> Actor.system();
            case OPS -> Actor.ops(UUID.randomUUID());
            case CREATOR -> Actor.creator(job.creatorId());
            case BRAND_ADMIN -> Actor.brandAdmin(UUID.randomUUID(), job.brandOrgId());
            case BRAND_MEMBER -> Actor.brandMember(UUID.randomUUID(), job.brandOrgId());
        };
    }
}
