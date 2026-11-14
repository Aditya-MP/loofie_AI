package com.loofie.core.ops.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

@IntegrationTest
@AutoConfigureMockMvc
class OpsJobApiTest {

    @Autowired MockMvc mvc;
    @Autowired JobFixtures fixtures;
    @Autowired JobTransitionService engine;
    @Autowired MutableClock clock;

    final UUID opsUser = UUID.randomUUID();

    @Test
    void requestsWithoutAnActorAreRejected() throws Exception {
        mvc.perform(get("/api/core/ops/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", is("https://loofie.app/problems/unauthenticated")));
    }

    @Test
    void nonOpsCannotUseTheConsole() throws Exception {
        mvc.perform(get("/api/core/ops/jobs")
                        .header("X-Dev-Role", "BRAND_ADMIN")
                        .header("X-Dev-User-Id", UUID.randomUUID().toString())
                        .header("X-Dev-Org-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listFiltersByBrandAndStatus() throws Exception {
        Seeded paid = fixtures.jobIn(JobStatus.PAID);
        Seeded otherBrand = fixtures.jobIn(JobStatus.PAID);

        mvc.perform(asOps(get("/api/core/ops/jobs"))
                        .param("brandOrgId", paid.brandOrgId().toString())
                        .param("status", "PAID", "IN_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].jobId", is(paid.jobId().toString())))
                .andExpect(jsonPath("$.items[0].status", is("PAID")));

        mvc.perform(asOps(get("/api/core/ops/jobs"))
                        .param("brandOrgId", otherBrand.brandOrgId().toString())
                        .param("status", "IN_REVIEW"))
                .andExpect(jsonPath("$.total", is(0)));
    }

    @Test
    void overdueFilterFindsJobsPastTheirDeadline() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.DRAFT_SUBMITTED);
        engine.transition(job.jobId(), JobAction.PREVIEW_READY, Actor.system(), null); // review clock starts

        mvc.perform(asOps(get("/api/core/ops/jobs")).param("brandOrgId", job.brandOrgId().toString()).param("overdue", "true"))
                .andExpect(jsonPath("$.total", is(0)));

        clock.advance(JobDeadlines.REVIEW_WINDOW.plus(Duration.ofMinutes(1)));
        mvc.perform(asOps(get("/api/core/ops/jobs")).param("brandOrgId", job.brandOrgId().toString()).param("overdue", "true"))
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].nextDeadline.name", is("review_due_at")))
                .andExpect(jsonPath("$.items[0].nextDeadline.overdue", is(true)));
    }

    @Test
    void detailShowsAssignmentsAndTimeline() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.PAID);
        UUID creator = UUID.randomUUID();

        mvc.perform(asOps(post("/api/core/jobs/{id}/transitions", job.jobId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"INVITE\",\"reason\":\"best match\",\"creatorId\":\"" + creator + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to", is("CREATOR_INVITED")));

        mvc.perform(asOps(get("/api/core/ops/jobs/{id}", job.jobId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status", is("CREATOR_INVITED")))
                .andExpect(jsonPath("$.job.activeCreatorId", is(creator.toString())))
                .andExpect(jsonPath("$.deadlines[0].name", is("accept_due_at")))
                .andExpect(jsonPath("$.assignments", hasSize(1)))
                .andExpect(jsonPath("$.timeline", hasSize(1)))
                .andExpect(jsonPath("$.timeline[0].action", is("INVITE")))
                .andExpect(jsonPath("$.timeline[0].actorType", is("OPS")))
                .andExpect(jsonPath("$.timeline[0].reason", is("best match")))
                .andExpect(jsonPath("$.timeline[0].payload.toState", is("CREATOR_INVITED")));
    }

    @Test
    void transitionErrorsMapToProblemResponses() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.PAID);

        mvc.perform(asOps(post("/api/core/jobs/{id}/transitions", job.jobId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", is("https://loofie.app/problems/illegal-transition")))
                .andExpect(jsonPath("$.currentState", is("PAID")));

        mvc.perform(asOps(post("/api/core/jobs/{id}/transitions", job.jobId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CANCEL\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", is("https://loofie.app/problems/rule-violated")));

        mvc.perform(asOps(post("/api/core/jobs/{id}/transitions", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CANCEL\",\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(asOps(post("/api/core/jobs/{id}/transitions", job.jobId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no action\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/core/jobs/{id}/transitions", job.jobId())
                        .header("X-Dev-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PAYMENT_CONFIRMED\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/core/jobs/{id}/transitions", job.jobId())
                        .header("X-Dev-Role", "BRAND_ADMIN")
                        .header("X-Dev-User-Id", UUID.randomUUID().toString())
                        .header("X-Dev-Org-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CANCEL\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder asOps(MockHttpServletRequestBuilder request) {
        return request.header("X-Dev-Role", "OPS").header("X-Dev-User-Id", opsUser.toString());
    }
}
