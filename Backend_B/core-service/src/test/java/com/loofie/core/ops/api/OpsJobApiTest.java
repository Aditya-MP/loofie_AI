package com.loofie.core.ops.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    @Autowired com.loofie.core.job.infra.JobRepository jobRepo;

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

    @Test
    void opsEditsTheHolidayTable() throws Exception {
        String body = "{\"date\":\"2027-11-01\",\"stateCode\":\"KA\",\"name\":\"Karnataka Rajyotsava\"}";
        mvc.perform(asOps(put("/api/core/ops/holidays")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(asOps(put("/api/core/ops/holidays")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mvc.perform(asOps(get("/api/core/ops/holidays")).param("year", "2027"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.stateCode == 'KA')].name", hasSize(1)));

        mvc.perform(put("/api/core/ops/holidays")
                        .header("X-Dev-Role", "BRAND_ADMIN")
                        .header("X-Dev-User-Id", UUID.randomUUID().toString())
                        .header("X-Dev-Org-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(asOps(delete("/api/core/ops/holidays/2027-11-01")).param("stateCode", "KA"))
                .andExpect(status().isNoContent());
        mvc.perform(asOps(delete("/api/core/ops/holidays/2027-11-01")).param("stateCode", "KA"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shippingThroughTheApiNeedsTrackingAndShowsInTheDetail() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        UUID brandUser = UUID.randomUUID();

        mvc.perform(post("/api/core/jobs/{id}/transitions", job.jobId())
                        .header("X-Dev-Role", "BRAND_MEMBER")
                        .header("X-Dev-User-Id", brandUser.toString())
                        .header("X-Dev-Org-Id", job.brandOrgId().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"MARK_SHIPPED\",\"courier\":\"Delhivery\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(post("/api/core/jobs/{id}/transitions", job.jobId())
                        .header("X-Dev-Role", "BRAND_MEMBER")
                        .header("X-Dev-User-Id", brandUser.toString())
                        .header("X-Dev-Org-Id", job.brandOrgId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MARK_SHIPPED\",\"courier\":\"Delhivery\",\"trackingNumber\":\"DL42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to", is("PRODUCT_SHIPPED")));

        mvc.perform(post("/api/core/jobs/{id}/transitions", job.jobId())
                        .header("X-Dev-Role", "CREATOR")
                        .header("X-Dev-User-Id", job.creatorId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REPORT_DELIVERY_ISSUE\",\"issueType\":\"DAMAGED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to", is("DELIVERY_ISSUE")));

        mvc.perform(asOps(get("/api/core/ops/jobs/{id}", job.jobId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryIssueType", is("DAMAGED")))
                .andExpect(jsonPath("$.shipments", hasSize(1)))
                .andExpect(jsonPath("$.shipments[0].trackingNumber", is("DL42")))
                .andExpect(jsonPath("$.deadlines[0].name", is("issue_response_due_at")));
    }

    @Test
    void aPassedReminderIsNotOverdueButAMissedShippingDeadlineIs() throws Exception {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_INVITED);
        engine.transition(job.jobId(), JobAction.ACCEPT, Actor.creator(job.creatorId()), null);
        var accepted = jobRepo.findById(job.jobId()).orElseThrow();

        clock.set(accepted.getShipReminderAt().plusSeconds(60));
        mvc.perform(asOps(get("/api/core/ops/jobs")).param("brandOrgId", job.brandOrgId().toString()).param("overdue", "true"))
                .andExpect(jsonPath("$.total", is(0)));
        mvc.perform(asOps(get("/api/core/ops/jobs/{id}", job.jobId())))
                .andExpect(jsonPath("$.job.nextDeadline.name", is("ship_reminder_at")))
                .andExpect(jsonPath("$.job.nextDeadline.overdue", is(false)));

        clock.set(accepted.getShipDueAt().plusSeconds(60));
        mvc.perform(asOps(get("/api/core/ops/jobs")).param("brandOrgId", job.brandOrgId().toString()).param("overdue", "true"))
                .andExpect(jsonPath("$.total", is(1)));
    }

    private MockHttpServletRequestBuilder asOps(MockHttpServletRequestBuilder request) {
        return request.header("X-Dev-Role", "OPS").header("X-Dev-User-Id", opsUser.toString());
    }
}
