package com.loofie.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.job.app.JobNoticeService;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

/** The inbox (R5), the senior ops roles (R7), the review clock switch (R6) and the refund API (R2) over HTTP. */
@IntegrationTest
@AutoConfigureMockMvc
class InboxRolesAndApiTest {

    @Autowired MockMvc mvc;
    @Autowired JobNoticeService notices;
    @Autowired JobTransitionService engine;
    @Autowired JobFixtures fixtures;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    // ---- R5 inbox ----

    @Test
    void aReminderReachesTheBrandItIsForAndNobodyElse() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        notices.raise(new JobNoticeService.Notice(job.jobId(), job.orderId(), 1, JobStatus.CREATOR_ACCEPTED,
                "SHIP_REMINDER", clock.instant().plus(Duration.ofDays(1)), List.of("BRAND", "OPS")), clock.instant());

        String page = mvc.perform(asBrand(get("/api/core/notifications"), job.brandOrgId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'SHIP_REMINDER' && @.jobId == '" + job.jobId() + "')]", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.<List<String>>read(page,
                "$[?(@.kind == 'SHIP_REMINDER')].id").getFirst();

        mvc.perform(asBrand(get("/api/core/notifications"), UUID.randomUUID()))
                .andExpect(jsonPath("$[?(@.jobId == '" + job.jobId() + "')]", hasSize(0)));
        mvc.perform(asCreator(get("/api/core/notifications"), job.creatorId()))
                .andExpect(jsonPath("$[?(@.jobId == '" + job.jobId() + "')]", hasSize(0)));
        mvc.perform(asRole(get("/api/core/notifications").param("jobId", job.jobId().toString()), "OPS"))
                .andExpect(jsonPath("$[?(@.kind == 'SHIP_REMINDER' && @.jobId == '" + job.jobId() + "')]", hasSize(1)));

        mvc.perform(asBrand(post("/api/core/notifications/{id}/read", id), UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mvc.perform(asBrand(post("/api/core/notifications/{id}/read", id), job.brandOrgId()))
                .andExpect(status().isNoContent());
        mvc.perform(asBrand(get("/api/core/notifications").param("unreadOnly", "true"), job.brandOrgId()))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]", hasSize(0)));
        mvc.perform(asBrand(get("/api/core/notifications").param("limit", "1000"), job.brandOrgId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCreatorReminderReachesThatCreator() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        notices.raise(new JobNoticeService.Notice(job.jobId(), job.orderId(), 1, JobStatus.IN_PRODUCTION,
                "PRODUCTION_DUE_24H", clock.instant().plus(Duration.ofDays(1)), List.of("CREATOR")), clock.instant());
        mvc.perform(asCreator(get("/api/core/notifications"), job.creatorId()))
                .andExpect(jsonPath("$[?(@.kind == 'PRODUCTION_DUE_24H')].payload.dueAt", hasSize(1)));
    }

    // ---- R7 roles ----

    @Test
    void seniorOpsCanDoEverythingOpsCanAndTheLogKeepsTheirRealRole() {
        Seeded job = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        fixtures.setDeadline(job.jobId(), "production_due_at", clock.instant().plus(Duration.ofDays(2)));
        engine.transition(job.jobId(), JobAction.OPS_EXTEND, Actor.opsLead(UUID.randomUUID()), "shoot location closed");
        engine.transition(job.jobId(), JobAction.OPS_EXTEND, Actor.founder(UUID.randomUUID()), "and again");
        assertThat(jdbc.sql("SELECT actor_type FROM job_events WHERE job_id = ? AND action = 'OPS_EXTEND' ORDER BY job_version")
                .param(job.jobId()).query(String.class).list()).containsExactly("OPS_LEAD", "FOUNDER");
    }

    // ---- R6 review clock ----

    @Test
    void theReviewClockIs120HoursUntilTheWorkingDaySwitchIsOn() {
        Job job = Job.forNewOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, 500000, clock.instant());
        job.moveTo(JobStatus.IN_REVIEW, clock.instant());
        Instant fakeWorkingDayEnd = Instant.parse("2030-01-01T00:00:00Z");
        JobDeadlines.apply(job, clock.instant(), (from, days, state) -> {
            assertThat(days).isEqualTo(5);
            return fakeWorkingDayEnd;
        }, null);
        assertThat(job.getReviewDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.REVIEW_WINDOW));

        JobDeadlines.apply(job, clock.instant(), (from, days, state) -> fakeWorkingDayEnd, 5);
        assertThat(job.getReviewDueAt()).isEqualTo(fakeWorkingDayEnd);
    }

    // ---- R2 over HTTP ----

    @Test
    void refundsOverHttpNeedAKeyAndARetriedApprovalNeverApprovesTwice() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.PAID);
        String body = "{\"action\":\"CANCEL\",\"reasonCode\":\"BRAND_CANCELLED\",\"note\":\"brand withdrew\"}";
        mvc.perform(asRole(post("/api/core/ops/jobs/{id}/refund-requests", job.jobId()), "OPS_LEAD")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionRequired());

        UUID leadUser = UUID.randomUUID();
        String created = mvc.perform(asUser(post("/api/core/ops/jobs/{id}/refund-requests", job.jobId()), "OPS_LEAD", leadUser)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brandCashPaise", is(678500)))
                .andExpect(jsonPath("$.status", is("REQUESTED")))
                .andReturn().getResponse().getContentAsString();
        String requestId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(asUser(post("/api/core/ops/refund-requests/{id}/approve", requestId), "OPS_LEAD", leadUser)
                            .header("Idempotency-Key", key))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("APPROVED")));
        }
        assertThat(fixtures.status(job.jobId())).isEqualTo("CANCELLED");

        // The plain transition API never moves money.
        Seeded other = fixtures.jobIn(JobStatus.PAID);
        mvc.perform(asRole(post("/api/core/jobs/{id}/transitions", other.jobId()), "OPS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CANCEL\",\"reason\":\"x\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(asRole(get("/api/core/ops/refund-requests"), "BRAND_ADMIN").header("X-Dev-Org-Id",
                UUID.randomUUID().toString())).andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder asBrand(MockHttpServletRequestBuilder r, UUID org) {
        return r.header("X-Dev-Role", "BRAND_ADMIN").header("X-Dev-User-Id", UUID.randomUUID().toString())
                .header("X-Dev-Org-Id", org.toString());
    }

    private MockHttpServletRequestBuilder asCreator(MockHttpServletRequestBuilder r, UUID creator) {
        return r.header("X-Dev-Role", "CREATOR").header("X-Dev-User-Id", creator.toString());
    }

    private MockHttpServletRequestBuilder asRole(MockHttpServletRequestBuilder r, String role) {
        return asUser(r, role, UUID.randomUUID());
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder r, String role, UUID user) {
        return r.header("X-Dev-Role", role).header("X-Dev-User-Id", user.toString());
    }
}
