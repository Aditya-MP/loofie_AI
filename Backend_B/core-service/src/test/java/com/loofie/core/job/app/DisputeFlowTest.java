package com.loofie.core.job.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.common.Ids;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.ReasonCode;
import com.loofie.core.job.timer.DeadlineWorker;
import com.loofie.core.refund.app.RefundService;
import com.loofie.core.refund.domain.RefundReason;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F9 disputes and replacement: swap offers (ops offers, the brand decides), the 10-day window after a decline, the
 * level drop after a missed deadline, outcome notices and the creator's statement.
 */
@IntegrationTest
@AutoConfigureMockMvc
class DisputeFlowTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JobFixtures fixtures;
    @Autowired JobTransitionService engine;
    @Autowired SwapOfferService swaps;
    @Autowired DisputeService disputes;
    @Autowired RefundService refunds;
    @Autowired DeadlineWorker timer;
    @Autowired MutableClock clock;

    final Actor ops = Actor.ops(UUID.randomUUID());
    final Actor lead = Actor.opsLead(UUID.randomUUID());

    // ------------------------------------------------------------------ offers

    @Test
    void opsOffersOneBookableCreatorAtTheSameOrALowerRate() {
        Seeded job = fixtures.jobIn(JobStatus.REPLACEMENT_NEEDED); // creator fee Rs 5,000
        UUID ok = bookableCreator(4000, "VERIFIED");

        assertThatThrownBy(() -> swaps.offer(Actor.brandAdmin(UUID.randomUUID(), job.brandOrgId()), job.jobId(), ok,
                "brand tries", null)).isInstanceOf(ActorNotAllowedException.class);
        assertThatThrownBy(() -> swaps.offer(ops, job.jobId(), ok, "hi", null)).hasMessageContaining("note");
        assertThatThrownBy(() -> swaps.offer(ops, job.jobId(), notBookableCreator(), "next on the list", null))
                .hasMessageContaining("cannot be booked");
        assertThatThrownBy(() -> swaps.offer(ops, job.jobId(), bookableCreator(5001, "NEW"), "next on the list", null))
                .as("one rupee above the fee").hasMessageContaining("above the job's creator fee");
        assertThatThrownBy(() -> swaps.offer(ops, fixtures.jobIn(JobStatus.IN_PRODUCTION).jobId(), ok, "next on list",
                null)).as("job has a creator").hasMessageContaining("only while the job has none");

        SwapOfferService.OfferView o = swaps.offer(ops, job.jobId(), ok, "next on the shortlist", "sug-1");
        assertThat(o.status()).isEqualTo("OFFERED");
        assertThat(o.ratePerReelRupees()).isEqualTo(4000);
        assertThat(notices(job.jobId(), "SWAP_OFFERED", "BRAND_ORG")).isEqualTo(1);
        assertThat(swaps.offer(ops, fixtures.jobIn(JobStatus.REPLACEMENT_NEEDED).jobId(), bookableCreator(5000, "NEW"),
                "same rate is fine", null).status()).isEqualTo("OFFERED");

        assertThatThrownBy(() -> swaps.offer(ops, job.jobId(), bookableCreator(3000, "NEW"), "second one", null))
                .hasMessageContaining("already has an open offer");
        swaps.withdraw(ops, job.jobId(), "found someone better");
        assertThat(swaps.offer(ops, job.jobId(), bookableCreator(3000, "NEW"), "second one", null).status())
                .isEqualTo("OFFERED");
    }

    @Test
    void theBrandAdminApprovesTheOfferedCreatorAndNoOneElse() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.REPLACEMENT_NEEDED);
        UUID offered = bookableCreator(4500, "VERIFIED");
        swaps.offer(ops, job.jobId(), offered, "next on the shortlist", null);
        UUID admin = UUID.randomUUID();

        JsonNode view = body(call(get("/api/core/brand/jobs/{id}/swap-offer", job.jobId()), "BRAND_MEMBER",
                UUID.randomUUID(), job.brandOrgId(), null), 200);
        assertThat(view.get("creator").get("creatorId").asString()).isEqualTo(offered.toString());
        assertThat(view.get("creator").get("ratePerReelRupees").asInt()).isEqualTo(4500);
        assertThat(view.get("creator").has("phone")).as("no contact details").isFalse();
        assertThat(view.toString()).doesNotContain("next on the shortlist"); // the ops note stays inside
        assertThat(call(get("/api/core/brand/jobs/{id}/swap-offer", job.jobId()), "BRAND_ADMIN", UUID.randomUUID(),
                Ids.newId(), null).getStatus()).as("another brand").isEqualTo(404);
        assertThat(call(post("/api/core/brand/jobs/{id}/swap-offer/approve", job.jobId()), "BRAND_MEMBER",
                UUID.randomUUID(), job.brandOrgId(), null).getStatus()).as("member").isEqualTo(403);

        // The old way (any creator id straight to the engine) is closed.
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(job.jobId(), JobAction.APPROVE_SWAP,
                Actor.brandAdmin(admin, job.brandOrgId()), null).withCreator(UUID.randomUUID())))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("No open offer");

        JsonNode moved = body(call(post("/api/core/brand/jobs/{id}/swap-offer/approve", job.jobId()), "BRAND_ADMIN",
                admin, job.brandOrgId(), null), 200);
        assertThat(moved.get("to").asString()).isEqualTo("CREATOR_INVITED");
        assertThat(jdbc.sql("SELECT creator_id FROM assignments WHERE job_id = ? AND status = 'ACTIVE'")
                .param(job.jobId()).query(UUID.class).single()).isEqualTo(offered);
        assertThat(jdbc.sql("SELECT status FROM job_swap_offers WHERE job_id = ?").param(job.jobId())
                .query(String.class).single()).isEqualTo("APPROVED");
        assertThat(notices(job.jobId(), "JOB_INVITED", "CREATOR")).isEqualTo(1);
        assertThat(call(get("/api/core/brand/jobs/{id}/swap-offer", job.jobId()), "BRAND_ADMIN", admin,
                job.brandOrgId(), null).getStatus()).as("no open offer any more").isEqualTo(404);
    }

    @Test
    void theBrandDeclinesWithAReasonAndOpsOffersAnother() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.REPLACEMENT_NEEDED);
        swaps.offer(ops, job.jobId(), bookableCreator(4000, "NEW"), "next on the shortlist", null);
        Actor admin = Actor.brandAdmin(UUID.randomUUID(), job.brandOrgId());

        assertThatThrownBy(() -> swaps.decline(admin, job.jobId(), "no")).hasMessageContaining("reason");
        assertThat(call(post("/api/core/brand/jobs/{id}/swap-offer/decline", job.jobId()), "BRAND_ADMIN",
                admin.userId(), job.brandOrgId(), Map.of("reason", "Not our category")).getStatus()).isEqualTo(204);
        assertThat(notices(job.jobId(), "SWAP_DECLINED", "OPS")).isEqualTo(1);
        assertThatThrownBy(() -> swaps.decline(admin, job.jobId(), "again please")).isInstanceOf(
                JobNotFoundException.class);

        swaps.offer(ops, job.jobId(), bookableCreator(4000, "NEW"), "a beauty creator this time", null);
        List<SwapOfferService.OfferView> all = swaps.forOps(ops, job.jobId());
        assertThat(all).extracting(SwapOfferService.OfferView::status).containsExactly("DECLINED", "OFFERED");
        assertThat(all.get(0).declineReason()).isEqualTo("Not our category");
    }

    @Test
    void twoOffersAtTheSameMomentLeaveOne() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.REPLACEMENT_NEEDED);
        List<UUID> creators = List.of(bookableCreator(4000, "NEW"), bookableCreator(4000, "NEW"),
                bookableCreator(4000, "NEW"), bookableCreator(4000, "NEW"));
        int ok = 0;
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (UUID c : creators) {
                Callable<Boolean> offer = () -> {
                    try {
                        swaps.offer(ops, job.jobId(), c, "racing offers", null);
                        return true;
                    } catch (TransitionGuardException e) {
                        return false;
                    }
                };
                results.add(pool.submit(offer));
            }
            for (Future<Boolean> f : results) {
                ok += f.get() ? 1 : 0;
            }
        }
        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM job_swap_offers WHERE job_id = ? AND status = 'OFFERED'")
                .param(job.jobId()).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ missed deadline

    @Test
    void aMissedDeadlineDropsTheCreatorToNewAndSheCannotBeOfferedTheSameJob() {
        UUID creator = bookableCreator(4000, "VERIFIED");
        Seeded job = inProductionWith(creator);
        fixtures.setDeadline(job.jobId(), "production_due_at", clock.instant().minus(Duration.ofMinutes(1)));

        engine.transition(job.jobId(), JobAction.PRODUCTION_EXPIRED, Actor.system(), null);

        assertThat(fixtures.status(job.jobId())).isEqualTo("REPLACEMENT_NEEDED");
        assertThat(level(creator)).isEqualTo("NEW");
        assertThat(jdbc.sql("SELECT level_reason FROM creator_profiles WHERE user_id = ?").param(creator)
                .query(String.class).single()).contains(job.jobId().toString());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'CREATOR_LEVEL_CHANGED' AND target_user_id = ?"
                + " AND actor_role = 'SYSTEM'").param(creator).query(Long.class).single()).isEqualTo(1);
        assertThat(notices(job.jobId(), "LEVEL_DROPPED_MISSED_DEADLINE", "CREATOR")).isEqualTo(1);
        assertThat(notices(job.jobId(), "REPLACEMENT_STARTED", "BRAND_ORG")).isEqualTo(1);
        assertThat(notices(job.jobId(), "REPLACEMENT_NEEDED", "OPS")).isEqualTo(1);

        assertThatThrownBy(() -> swaps.offer(ops, job.jobId(), creator, "she is back", null))
                .hasMessageContaining("already on this job");

        // Already NEW: a second miss changes nothing and writes nothing.
        Seeded again = inProductionWith(creator);
        fixtures.setDeadline(again.jobId(), "production_due_at", clock.instant().minus(Duration.ofMinutes(1)));
        engine.transition(again.jobId(), JobAction.PRODUCTION_EXPIRED, Actor.system(), null);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'CREATOR_LEVEL_CHANGED' AND target_user_id = ?")
                .param(creator).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ decline: 10 days, then a full refund

    @Test
    void aDeclinedJobGetsTheTenDayWindowThenAFullRefund() {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_INVITED);
        Instant now = clock.instant();
        engine.transition(job.jobId(), JobAction.DECLINE, Actor.creator(job.creatorId()), null);

        assertThat(fixtures.status(job.jobId())).isEqualTo("DECLINED");
        Instant due = jdbc.sql("SELECT replacement_due_at FROM jobs WHERE id = ?").param(job.jobId())
                .query(java.time.OffsetDateTime.class).single().toInstant();
        assertThat(due).isEqualTo(now.plus(Duration.ofDays(10)));
        assertThat(notices(job.jobId(), "REPLACEMENT_STARTED", "BRAND_ORG")).isEqualTo(1);

        swaps.offer(ops, job.jobId(), bookableCreator(4000, "NEW"), "offer before the window ends", null);
        fixtures.setDeadline(job.jobId(), "replacement_due_at", clock.instant().minus(Duration.ofMinutes(1)));
        timer.runOnce();
        var open = jdbc.sql("SELECT id, refund_case, reason_code FROM refund_requests WHERE job_id = ?")
                .param(job.jobId()).query().singleRow();
        assertThat(open.get("refund_case")).isEqualTo("FULL");
        assertThat(open.get("reason_code")).isEqualTo("LOOFIE_NO_CREATOR");
        refunds.approve(lead, (UUID) open.get("id"), null);

        assertThat(fixtures.status(job.jobId())).isEqualTo("REFUNDED");
        assertThat(jdbc.sql("SELECT status || ':' || closed_reason FROM job_swap_offers WHERE job_id = ?")
                .param(job.jobId()).query(String.class).single()).as("the open offer is closed with the job")
                .isEqualTo("WITHDRAWN:REFUNDED");
    }

    @Test
    void opsCanRefundADeclinedJobAtOnce() {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_INVITED);
        engine.transition(job.jobId(), JobAction.DECLINE, Actor.creator(job.creatorId()), null);
        var r = refunds.request(ops, job.jobId(), JobAction.RESOLVE_REFUND, RefundReason.LOOFIE_NO_CREATOR,
                "brand does not want to wait");
        refunds.approve(lead, r.id(), null);
        assertThat(fixtures.status(job.jobId())).isEqualTo("REFUNDED");
    }

    // ------------------------------------------------------------------ disputes

    @Test
    void theCreatorStatesHerSideOnceWhileDisputedAndOpsReadsIt() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.DISPUTED);
        String text = "I followed every checklist item; the hook is in the first 2 seconds.";

        assertThat(call(post("/api/core/creator/jobs/{id}/dispute-statement", job.jobId()), "CREATOR",
                UUID.randomUUID(), null, Map.of("statement", text)).getStatus()).as("not her job").isEqualTo(404);
        assertThatThrownBy(() -> disputes.statement(Actor.creator(job.creatorId()), job.jobId(), "too short"))
                .hasMessageContaining("characters");
        assertThat(call(post("/api/core/creator/jobs/{id}/dispute-statement", job.jobId()), "CREATOR",
                job.creatorId(), null, Map.of("statement", text)).getStatus()).isEqualTo(201);
        assertThatThrownBy(() -> disputes.statement(Actor.creator(job.creatorId()), job.jobId(), text))
                .hasMessageContaining("already sent");
        assertThat(notices(job.jobId(), "DISPUTE_STATEMENT_ADDED", "OPS")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT payload::text FROM notifications WHERE job_id = ? AND kind = 'DISPUTE_STATEMENT_ADDED'")
                .param(job.jobId()).query(String.class).single()).as("the text stays out of inboxes").doesNotContain("hook");

        JsonNode view = body(call(get("/api/core/ops/jobs/{id}/dispute", job.jobId()), "OPS", ops.userId(), null,
                null), 200);
        assertThat(view.get("statements").get(0).get("statement").asString()).isEqualTo(text);
        assertThat(call(get("/api/core/ops/jobs/{id}/dispute", job.jobId()), "BRAND_ADMIN", UUID.randomUUID(),
                job.brandOrgId(), null).getStatus()).isEqualTo(403);

        Seeded inReview = fixtures.jobIn(JobStatus.IN_REVIEW);
        assertThatThrownBy(() -> disputes.statement(Actor.creator(inReview.creatorId()), inReview.jobId(), text))
                .hasMessageContaining("only while");
    }

    @Test
    void everyDisputeOutcomeReachesTheRightPeople() {
        Seeded approved = fixtures.jobIn(JobStatus.DISPUTED);
        engine.transition(approved.jobId(), JobAction.RESOLVE_APPROVE, ops, "video meets the checklist");
        assertThat(notices(approved.jobId(), "DISPUTE_RESOLVED_APPROVED", "CREATOR")).isEqualTo(1);
        assertThat(notices(approved.jobId(), "DISPUTE_RESOLVED_APPROVED", "BRAND_ORG")).isEqualTo(1);

        Seeded replaced = fixtures.jobIn(JobStatus.DISPUTED);
        engine.transition(TransitionCommand.of(replaced.jobId(), JobAction.RESOLVE_REPLACE, ops, "hook missing")
                .with(new TransitionDetails(null, null, null, null, ReasonCode.OTHER)));
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE job_id = ? AND kind = 'DISPUTE_RESOLVED_REPLACED' "
                        + "AND recipient_id = ?").param(replaced.jobId()).param(replaced.creatorId())
                .query(Long.class).single()).as("the creator who left").isEqualTo(1);
        assertThat(notices(replaced.jobId(), "REPLACEMENT_STARTED", "BRAND_ORG")).isEqualTo(1);

        Seeded refunded = fixtures.jobIn(JobStatus.DISPUTED);
        var r = refunds.request(ops, refunded.jobId(), JobAction.RESOLVE_REFUND, RefundReason.DISPUTE_UPHELD,
                "brand was right", ReasonCode.OTHER);
        refunds.approve(lead, r.id(), null);
        assertThat(notices(refunded.jobId(), "DISPUTE_RESOLVED_REFUNDED", "CREATOR")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private Seeded inProductionWith(UUID creator) {
        Seeded job = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        jdbc.sql("UPDATE assignments SET creator_id = ? WHERE job_id = ?").params(creator, job.jobId()).update();
        return new Seeded(job.orderId(), job.jobId(), job.brandOrgId(), creator);
    }

    private UUID bookableCreator(int rateRupees, String level) {
        UUID id = creatorUser();
        jdbc.sql("""
                INSERT INTO creator_profiles (user_id, display_name, city, state, languages, categories, formats,
                    rate_per_reel_rupees, partnership_ads, turnaround_days, sample_links, instagram_handle,
                    marketplace_status, review_status, completed_at, level, level_reason, created_at, updated_at)
                VALUES (:id, 'Swap Test', 'Pune', 'MH', :langs, :cats, :formats, :rate, true, 5, :links, :handle,
                        'LISTED', 'APPROVED', now(), :level, :why, now(), now())""")
                .param("id", id).param("langs", new String[] {"Hindi"}).param("cats", new String[] {"other"})
                .param("formats", new String[] {"REEL"}).param("rate", rateRupees)
                .param("links", new String[] {"https://www.instagram.com/reel/x/"})
                .param("handle", "swap." + id.toString().substring(0, 8)).param("level", level)
                .param("why", "VERIFIED".equals(level) ? "two on-time jobs" : null).update();
        return id;
    }

    private UUID notBookableCreator() {
        UUID id = bookableCreator(1000, "NEW");
        jdbc.sql("UPDATE creator_profiles SET review_status = 'PENDING' WHERE user_id = ?").param(id).update();
        return id;
    }

    private UUID creatorUser() {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO users (id, account_type, status, phone, phone_verified_at, adult_confirmed_at,
                                   onboarding_completed, created_at, updated_at)
                VALUES (:id, 'CREATOR', 'ACTIVE', :phone, now(), now(), true, now(), now())""")
                .param("id", id).param("phone", "+9196" + String.format("%08d", Math.floorMod(id.hashCode(), 100_000_000)))
                .update();
        return id;
    }

    private String level(UUID creator) {
        return jdbc.sql("SELECT level FROM creator_profiles WHERE user_id = ?").param(creator).query(String.class)
                .single();
    }

    private long notices(UUID jobId, String kind, String recipientType) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE job_id = ? AND kind = ? AND recipient_type = ?")
                .params(jobId, kind, recipientType).query(Long.class).single();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b, String role, UUID user, UUID org,
                                         Object body) throws Exception {
        b.header("X-Dev-Role", role).header("X-Dev-User-Id", user.toString());
        if (org != null) {
            b.header("X-Dev-Org-Id", org.toString());
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse r, int status) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        return json.readTree(r.getContentAsString());
    }
}
