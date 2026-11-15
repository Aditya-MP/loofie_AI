package com.loofie.core.mustpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.TransitionRule;
import com.loofie.core.job.domain.TransitionTable;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;

/**
 * Must-pass: a brand member can never do what only the brand admin may do (approve releases payment,
 * swaps choose who gets paid). Driven by the transition table, so a new admin-only action is covered
 * automatically.
 */
@IntegrationTest
class BrandRoleMatrixTest {

    @Autowired JobTransitionService engine;
    @Autowired JobFixtures fixtures;

    @Test
    void aBrandMemberIsRefusedEveryAdminOnlyAction() {
        List<TransitionRule> adminOnly = TransitionTable.all().stream()
                .filter(r -> r.roles().contains(ActorRole.BRAND_ADMIN) && !r.roles().contains(ActorRole.BRAND_MEMBER))
                .filter(r -> r.from() != JobStatus.DRAFT_BRIEF) // order submission goes through intake, tested there
                .toList();
        assertThat(adminOnly).extracting(TransitionRule::action).isNotEmpty();

        for (TransitionRule rule : adminOnly) {
            Seeded job = fixtures.jobIn(rule.from(), false, true);
            Actor member = Actor.brandMember(UUID.randomUUID(), job.brandOrgId());
            assertThatThrownBy(() -> engine.transition(job.jobId(), rule.action(), member, "test"))
                    .as("%s from %s by a brand member", rule.action(), rule.from())
                    .isInstanceOf(ActorNotAllowedException.class);
            assertThat(fixtures.status(job.jobId())).isEqualTo(rule.from().name());
            assertThat(fixtures.eventCount(job.jobId())).isZero();
        }
    }
}
