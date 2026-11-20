package com.loofie.core.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.common.Ids;
import com.loofie.core.creator.app.OpsCreatorSearch;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.OpsTimeService;
import com.loofie.core.job.app.PayoutHoldService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.ops.app.OpsOverviewService;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F10 ops console: time log, payout holds, creator search, overview. */
@IntegrationTest
@AutoConfigureMockMvc
class OpsConsoleTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JobFixtures fixtures;
    @Autowired JobTransitionService engine;
    @Autowired OpsTimeService time;
    @Autowired PayoutHoldService holds;
    @Autowired OpsCreatorSearch search;
    @Autowired OpsOverviewService overview;
    @Autowired MutableClock clock;

    final Actor ops = Actor.ops(UUID.randomUUID());
    final Actor otherOps = Actor.ops(UUID.randomUUID());
    final Actor lead = Actor.opsLead(UUID.randomUUID());
    final Actor otherLead = Actor.opsLead(UUID.randomUUID());

    // ------------------------------------------------------------------ time log

    @Test
    void opsLogsMinutesPerJobOrCampaignAndFixesMistakesOnlyByCorrection() {
        Seeded job = fixtures.jobIn(JobStatus.IN_PRODUCTION);

        OpsTimeService.EntryView e = time.log(ops, job.jobId(), null, 45, "coordination", "called the brand");
        assertThat(e.orderId()).as("the job's order is filled in").isEqualTo(job.orderId());
        assertThat(e.activity()).isEqualTo("COORDINATION");
        time.log(ops, null, job.orderId(), 30, "SHORTLIST", null); // campaign work before any job
        assertThat(time.forJob(ops, job.jobId()).totalMinutes()).isEqualTo(45);
        assertThat(time.forOrder(ops, job.orderId()).totalMinutes()).isEqualTo(75);

        assertThatThrownBy(() -> time.log(ops, job.jobId(), null, 0, "OTHER", null)).hasMessageContaining("1-480");
        assertThatThrownBy(() -> time.log(ops, job.jobId(), null, 481, "OTHER", null)).hasMessageContaining("1-480");
        assertThatThrownBy(() -> time.log(ops, job.jobId(), null, 10, "LUNCH", null)).hasMessageContaining("activity");
        assertThatThrownBy(() -> time.log(ops, null, null, 10, "OTHER", null)).hasMessageContaining("which job");
        assertThatThrownBy(() -> time.log(ops, job.jobId(), Ids.newId(), 10, "OTHER", null))
                .hasMessageContaining("another order");
        assertThatThrownBy(() -> time.log(Actor.brandAdmin(UUID.randomUUID(), job.brandOrgId()), job.jobId(), null,
                10, "OTHER", null)).isInstanceOf(ActorNotAllowedException.class);

        assertThatThrownBy(() -> time.correct(otherOps, e.id(), "not my entry")).isInstanceOf(
                ActorNotAllowedException.class);
        OpsTimeService.EntryView fix = time.correct(ops, e.id(), "logged on the wrong job");
        assertThat(fix.minutes()).isEqualTo(-45);
        assertThat(fix.correctsId()).isEqualTo(e.id());
        assertThat(time.forJob(ops, job.jobId()).totalMinutes()).isZero();
        assertThatThrownBy(() -> time.correct(lead, e.id(), "again please")).hasMessageContaining("already corrected");
        assertThatThrownBy(() -> time.correct(ops, fix.id(), "undo the undo")).hasMessageContaining("itself a correction");

        OpsTimeService.EntryView other = time.log(otherOps, job.jobId(), null, 20, "DELIVERY", null);
        assertThat(time.correct(lead, other.id(), "lead fixes a typo").minutes()).as("a lead may correct anyone's")
                .isEqualTo(-20);

        // Append-only in the database itself.
        assertThatThrownBy(() -> jdbc.sql("UPDATE ops_time_entries SET minutes = 1 WHERE id = ?").param(e.id()).update())
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ops_time_entries WHERE id = ?").param(e.id()).update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void theMarginReportIsForLeadsAndShowsEachJobsFeeAndMinutes() {
        Seeded job = fixtures.jobIn(JobStatus.IN_REVIEW);
        Instant from = clock.instant().minusSeconds(1);
        time.log(ops, job.jobId(), null, 90, "DISPUTE", null);
        time.log(otherOps, job.jobId(), null, 30, "COORDINATION", null);
        Instant to = clock.instant().plusSeconds(1);

        assertThatThrownBy(() -> time.report(ops, from, to)).isInstanceOf(ActorNotAllowedException.class);
        assertThatThrownBy(() -> time.report(lead, to, from)).hasMessageContaining("from < to");
        List<OpsTimeService.ReportLine> lines = time.report(lead, from, to);
        OpsTimeService.ReportLine line = lines.stream().filter(l -> l.jobId().equals(job.jobId())).findFirst()
                .orElseThrow();
        assertThat(line.minutes()).isEqualTo(120);
        assertThat(line.creatorFeePaise()).isEqualTo(500000);
        assertThat(line.status()).isEqualTo("IN_REVIEW");
    }

    // ------------------------------------------------------------------ payout holds

    @Test
    void aHeldPayoutIsNotQueuedUntilAnotherSeniorReleasesIt() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.APPROVED);

        assertThatThrownBy(() -> holds.place(ops, fixtures.jobIn(JobStatus.IN_REVIEW).jobId(), "brand complained"))
                .hasMessageContaining("only while the job is APPROVED");
        assertThatThrownBy(() -> holds.place(ops, job.jobId(), "no")).hasMessageContaining("reason");
        holds.place(lead, job.jobId(), "brand says the video was reposted elsewhere");
        assertThatThrownBy(() -> holds.place(ops, job.jobId(), "second hold")).hasMessageContaining("already on hold");

        assertThatThrownBy(() -> engine.transition(job.jobId(), JobAction.PAYOUT_QUEUED, Actor.system(), null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("on hold");
        assertThat(holds.queue(ops, true, null, 200).items()).extracting(PayoutHoldService.QueueItem::jobId)
                .contains(job.jobId());

        assertThatThrownBy(() -> holds.release(ops, job.jobId(), "checked, fine")).isInstanceOf(
                ActorNotAllowedException.class);
        assertThatThrownBy(() -> holds.release(lead, job.jobId(), "checked, fine")).as("not the one who placed it")
                .isInstanceOf(ActorNotAllowedException.class);
        PayoutHoldService.HoldView released = holds.release(otherLead, job.jobId(), "checked with the brand, fine");
        assertThat(released.releasedBy()).isEqualTo(otherLead.userId());

        engine.transition(job.jobId(), JobAction.PAYOUT_QUEUED, Actor.system(), null);
        assertThat(fixtures.status(job.jobId())).isEqualTo("PAYOUT_PENDING");
        assertThat(jdbc.sql("SELECT string_agg(action, ',' ORDER BY at) FROM audit_log WHERE details->>'jobId' = ?")
                .param(job.jobId().toString()).query(String.class).single())
                .isEqualTo("PAYOUT_HOLD_PLACED,PAYOUT_HOLD_RELEASED");
        assertThat(holds.history(ops, job.jobId())).hasSize(1);

        MockHttpServletResponse r = call(get("/api/core/ops/payouts?held=true"), "BRAND_ADMIN", UUID.randomUUID(),
                job.brandOrgId());
        assertThat(r.getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ creator search

    @Test
    void opsFindsCreatorsByFiltersCheapestFirstWithoutContactDetails() throws Exception {
        String city = "Srch" + UUID.randomUUID().toString().substring(0, 6);
        UUID cheap = creator(city, 1000, "NEW", "APPROVED", "alpha");
        UUID mid = creator(city, 2000, "VERIFIED", "APPROVED", "beta");
        UUID pending = creator(city, 1500, "NEW", "PENDING", "gamma");

        assertThat(ids(search.search(ops, null, city, null, null, null, null, null, null, null, 25)))
                .as("bookable only, cheapest first").containsExactly(cheap, mid);
        assertThat(ids(search.search(ops, null, city, null, null, null, null, null, false, null, 25)))
                .containsExactly(cheap, pending, mid);
        assertThat(ids(search.search(ops, null, city, null, null, 1500, null, null, null, null, 25)))
                .containsExactly(cheap);
        assertThat(ids(search.search(ops, null, city, null, null, null, "verified", null, null, null, 25)))
                .containsExactly(mid);
        assertThat(ids(search.search(ops, "other", city, "mh", "Hindi", null, null, "@BET", null, null, 25)))
                .as("category, state, language, handle prefix").containsExactly(mid);

        OpsCreatorSearch.Page first = search.search(ops, null, city, null, null, null, null, null, null, null, 1);
        assertThat(ids(first)).containsExactly(cheap);
        assertThat(ids(search.search(ops, null, city, null, null, null, null, null, null, first.next(), 1)))
                .containsExactly(mid);

        JsonNode body = json.readTree(call(get("/api/core/ops/creators?city=" + city), "OPS", ops.userId(), null)
                .getContentAsString());
        assertThat(body.toString()).doesNotContain("+91").doesNotContain("phone");
        assertThat(call(get("/api/core/ops/creators?city=" + city), "BRAND_ADMIN", UUID.randomUUID(), Ids.newId())
                .getStatus()).isEqualTo(403);
        assertThatThrownBy(() -> search.search(ops, null, null, null, null, null, null, "a", null, null, 25))
                .hasMessageContaining("at least 2");
    }

    // ------------------------------------------------------------------ overview

    @Test
    void theOverviewCountsWhatNeedsAPersonAndRefreshesEvery30Seconds() throws Exception {
        OpsOverviewService.Overview before = overview.refresh();

        Seeded late = fixtures.jobIn(JobStatus.DISPUTED);
        fixtures.setDeadline(late.jobId(), "dispute_review_due_at", clock.instant().minusSeconds(60));
        holds.place(ops, fixtures.jobIn(JobStatus.APPROVED).jobId(), "checking the brand's complaint");
        fixtures.jobIn(JobStatus.REPLACEMENT_NEEDED);

        // Within 30 s the same snapshot is served.
        assertThat(overview.overview(ops).asOf()).isEqualTo(before.asOf());

        Instant start = clock.instant();
        try {
            clock.advance(Duration.ofSeconds(31));
            OpsOverviewService.Overview after = overview.overview(ops);
            assertThat(after.asOf()).isAfter(before.asOf());
            assertThat(after.queues().disputes() - before.queues().disputes()).isEqualTo(1);
            assertThat(after.queues().disputesOverdue() - before.queues().disputesOverdue()).isEqualTo(1);
            assertThat(after.queues().payoutHolds() - before.queues().payoutHolds()).isEqualTo(1);
            assertThat(after.queues().jobsWaitingForCreator() - before.queues().jobsWaitingForCreator()).isEqualTo(1);
        } finally {
            clock.set(start);
        }

        // A dispute opened and decided through the engine counts in the 30-day performance.
        Seeded review = fixtures.jobIn(JobStatus.IN_REVIEW, false, true);
        engine.transition(review.jobId(), JobAction.REJECT, Actor.brandAdmin(UUID.randomUUID(), review.brandOrgId()),
                "does not match the brief");
        engine.transition(review.jobId(), JobAction.RESOLVE_APPROVE, ops, "meets the checklist");
        OpsOverviewService.Overview perf = overview.refresh();
        assertThat(perf.last30Days().disputesDecided()).isGreaterThan(before.last30Days().disputesDecided());
        assertThat(perf.last30Days().disputesOnTimePercent()).isNotNull();

        assertThat(call(get("/api/core/ops/overview"), "OPS", ops.userId(), null).getStatus()).isEqualTo(200);
        assertThat(call(get("/api/core/ops/overview"), "CREATOR", UUID.randomUUID(), null).getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private static List<UUID> ids(OpsCreatorSearch.Page p) {
        return p.items().stream().map(OpsCreatorSearch.Result::creatorId).toList();
    }

    private UUID creator(String city, int rate, String level, String review, String handle) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO users (id, account_type, status, phone, phone_verified_at, adult_confirmed_at,
                                   onboarding_completed, created_at, updated_at)
                VALUES (:id, 'CREATOR', 'ACTIVE', :phone, now(), now(), true, now(), now())""")
                .param("id", id).param("phone", "+9194" + String.format("%08d", Math.floorMod(id.hashCode(), 100_000_000)))
                .update();
        jdbc.sql("""
                INSERT INTO creator_profiles (user_id, display_name, city, state, languages, categories, formats,
                    rate_per_reel_rupees, partnership_ads, turnaround_days, sample_links, instagram_handle,
                    marketplace_status, review_status, completed_at, level, level_reason, created_at, updated_at)
                VALUES (:id, 'Search Test', :city, 'MH', :langs, :cats, :formats, :rate, true, 5, :links, :handle,
                        'LISTED', :review, now(), :level, :why, now(), now())""")
                .param("id", id).param("city", city).param("langs", new String[] {"Hindi"})
                .param("cats", new String[] {"other"}).param("formats", new String[] {"REEL"}).param("rate", rate)
                .param("links", new String[] {"https://www.instagram.com/reel/x/"})
                .param("handle", handle + "." + id.toString().substring(0, 8)).param("review", review)
                .param("level", level).param("why", "VERIFIED".equals(level) ? "two on-time jobs" : null).update();
        return id;
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b, String role, UUID user, UUID org)
            throws Exception {
        b.header("X-Dev-Role", role).header("X-Dev-User-Id", user.toString());
        if (org != null) {
            b.header("X-Dev-Org-Id", org.toString());
        }
        return mvc.perform(b).andReturn().getResponse();
    }
}
