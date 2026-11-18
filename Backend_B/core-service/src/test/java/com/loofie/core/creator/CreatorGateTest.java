package com.loofie.core.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.creator.app.CreatorDirectory;
import com.loofie.core.creator.domain.CreatorProfile;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;

/**
 * Decision C6, with the switch on as in production: only a bookable creator (account active, passport complete,
 * listed) can accept an invitation.
 */
@IntegrationTest
@TestPropertySource(properties = "loofie.creators.require-bookable-to-accept=true")
class CreatorGateTest {

    @Autowired JobTransitionService engine;
    @Autowired JobFixtures fixtures;
    @Autowired JdbcClient jdbc;
    @Autowired CreatorDirectory directory;

    @Test
    void onlyABookableCreatorCanAcceptAnInvitation() {
        UUID creator = newCreatorUser();

        // no passport at all
        UUID job1 = invitedJobFor(creator);
        assertThatThrownBy(() -> engine.transition(job1, JobAction.ACCEPT, Actor.creator(creator), null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("Complete your profile");
        assertThat(fixtures.status(job1)).isEqualTo("CREATOR_INVITED");

        // complete but paused
        completeProfile(creator, CreatorProfile.MarketplaceStatus.HIDDEN);
        assertThat(directory.bookable(creator)).isFalse();
        assertThatThrownBy(() -> engine.transition(job1, JobAction.ACCEPT, Actor.creator(creator), null))
                .isInstanceOf(TransitionGuardException.class);

        // listed but still waiting for ops review: not offered, cannot accept (agreed 2026-11-18)
        jdbc.sql("UPDATE creator_profiles SET marketplace_status = 'LISTED' WHERE user_id = :id").param("id", creator)
                .update();
        assertThat(directory.bookable(creator)).isFalse();
        assertThatThrownBy(() -> engine.transition(job1, JobAction.ACCEPT, Actor.creator(creator), null))
                .isInstanceOf(TransitionGuardException.class);

        // approved and listed: accepted
        jdbc.sql("UPDATE creator_profiles SET review_status = 'APPROVED' WHERE user_id = :id").param("id", creator)
                .update();
        assertThat(directory.bookable(creator)).isTrue();
        engine.transition(job1, JobAction.ACCEPT, Actor.creator(creator), null);
        assertThat(fixtures.status(job1)).isEqualTo("CREATOR_ACCEPTED");

        // account suspended: not bookable even with a complete, listed passport
        jdbc.sql("UPDATE users SET status = 'SUSPENDED', status_reason = 'test' WHERE id = :id").param("id", creator)
                .update();
        UUID job2 = invitedJobFor(creator);
        assertThat(directory.bookable(creator)).isFalse();
        assertThatThrownBy(() -> engine.transition(job2, JobAction.ACCEPT, Actor.creator(creator), null))
                .isInstanceOf(TransitionGuardException.class);
    }

    private UUID invitedJobFor(UUID creator) {
        JobFixtures.Seeded s = fixtures.jobIn(JobStatus.CREATOR_INVITED);
        jdbc.sql("UPDATE assignments SET creator_id = :c WHERE job_id = :j").param("c", creator).param("j", s.jobId())
                .update();
        jdbc.sql("UPDATE jobs SET accept_due_at = :due WHERE id = :j").param("due", java.time.OffsetDateTime.ofInstant(
                Instant.parse("2030-01-01T00:00:00Z"), java.time.ZoneOffset.UTC)).param("j", s.jobId()).update();
        return s.jobId();
    }

    private UUID newCreatorUser() {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO users (id, account_type, status, phone, phone_verified_at, adult_confirmed_at,
                                   onboarding_completed, created_at, updated_at)
                VALUES (:id, 'CREATOR', 'ACTIVE', :phone, now(), now(), false, now(), now())""")
                .param("id", id).param("phone", "+9197" + String.format("%08d", Math.abs(id.hashCode()) % 100_000_000))
                .update();
        return id;
    }

    private void completeProfile(UUID creator, CreatorProfile.MarketplaceStatus status) {
        jdbc.sql("""
                INSERT INTO creator_profiles (user_id, display_name, city, state, languages, categories, formats,
                    rate_per_reel_rupees, partnership_ads, turnaround_days, sample_links, instagram_handle,
                    marketplace_status, review_status, completed_at, created_at, updated_at)
                VALUES (:id, 'Gate Test', 'Pune', 'MH', :langs, :cats, :formats, 2000, true, 5, :links, 'gate.test',
                        :status, 'PENDING', now(), now(), now())""")
                .param("id", creator).param("langs", List.of("Hindi").toArray(String[]::new))
                .param("cats", List.of("other").toArray(String[]::new))
                .param("formats", List.of("REEL").toArray(String[]::new))
                .param("links", List.of("https://www.instagram.com/reel/x/").toArray(String[]::new))
                .param("status", status.name()).update();
    }
}
