package com.loofie.core.job.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.common.Ids;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F7 D8: F6 photos are files the upload register issued to the person acting, attached once. */
@IntegrationTest
@AutoConfigureMockMvc
class JobPhotoUploadTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JobFixtures fixtures;

    @Test
    void aReceiptPhotoMustBeHerOwnUploadAndIsUsedOnce() throws Exception {
        JobFixtures.Seeded a = shipped();
        JobFixtures.Seeded b = shipped();

        // A made-up key in the right shape is no longer enough.
        assertThat(confirm(a, "receipts/" + UUID.randomUUID() + ".jpg").getStatus()).isEqualTo(422);
        // Only the right roles get a link for each kind; outsiders learn nothing about the job.
        assertThat(ticket(a.jobId(), "BRAND_ADMIN", UUID.randomUUID(), a.brandOrgId(), "RECEIPT_PHOTO").getStatus())
                .isEqualTo(403);
        assertThat(ticket(a.jobId(), "CREATOR", b.creatorId(), null, "RECEIPT_PHOTO").getStatus()).isEqualTo(404);
        assertThat(ticket(a.jobId(), "CREATOR", a.creatorId(), null, "HANDOVER_PHOTO").getStatus()).isEqualTo(403);

        String othersKey = upload(b, "RECEIPT_PHOTO");
        assertThat(confirm(a, othersKey).getStatus()).as("someone else's photo").isEqualTo(422);
        String mine = upload(a, "RECEIPT_PHOTO");
        assertThat(mine).matches("receipts/[0-9a-f-]{36}\\.jpg"); // the F6 key shape the shipments table checks
        MockHttpServletResponse ok = confirm(a, mine);
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(fixtures.status(a.jobId())).isEqualTo("IN_PRODUCTION");
        assertThat(jdbc.sql("SELECT used_at IS NOT NULL FROM uploads WHERE key = ?").param(mine).query(Boolean.class)
                .single()).isTrue();

        // The same photo cannot be attached to a second job.
        JobFixtures.Seeded c = shipped();
        assertThat(confirm(c, mine).getStatus()).isEqualTo(422);
    }

    @Test
    void aHandoverPhotoComesFromTheBrandsOwnUpload() throws Exception {
        JobFixtures.Seeded j = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        UUID admin = UUID.randomUUID();
        JsonNode t = body(ticket(j.jobId(), "BRAND_ADMIN", admin, j.brandOrgId(), "HANDOVER_PHOTO"));
        putFile(t, "image/jpeg");
        MockHttpServletResponse r = transition(j.jobId(), "BRAND_ADMIN", admin, j.brandOrgId(),
                Map.of("action", "MARK_SHIPPED", "courierCode", "LOCAL", "handoverPhotoRef", t.get("key").asString()));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(fixtures.status(j.jobId())).isEqualTo("PRODUCT_SHIPPED");
    }

    private JobFixtures.Seeded shipped() {
        JobFixtures.Seeded seeded = fixtures.jobIn(JobStatus.PRODUCT_SHIPPED);
        jdbc.sql("""
                INSERT INTO shipments (id, job_id, assignment_id, attempt, courier, tracking_number, shipped_at,
                                       shipped_by, version)
                SELECT ?, ?, a.id, 1, 'Delhivery', ?, now() - interval '1 day', ?, 0 FROM assignments a
                WHERE a.job_id = ? AND a.status = 'ACTIVE'
                """).params(Ids.newId(), seeded.jobId(), JobFixtures.trackingNumber(), UUID.randomUUID(), seeded.jobId())
                .update();
        return seeded;
    }

    private String upload(JobFixtures.Seeded j, String purpose) throws Exception {
        JsonNode t = body(ticket(j.jobId(), "CREATOR", j.creatorId(), null, purpose));
        putFile(t, "image/jpeg");
        return t.get("key").asString();
    }

    private void putFile(JsonNode ticket, String type) throws Exception {
        assertThat(mvc.perform(put(URI.create(ticket.get("url").asString())).header("Content-Type", type)
                .content(new byte[2048])).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse confirm(JobFixtures.Seeded j, String key) throws Exception {
        return transition(j.jobId(), "CREATOR", j.creatorId(), null, Map.of("action", "CONFIRM_RECEIVED", "photoRef", key));
    }

    private MockHttpServletResponse ticket(UUID jobId, String role, UUID user, UUID org, String purpose) throws Exception {
        return mvc.perform(as(post("/api/core/jobs/{id}/uploads", jobId), role, user, org)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("purpose", purpose, "contentType", "image/jpeg", "sizeBytes", 2048))))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse transition(UUID jobId, String role, UUID user, UUID org, Map<String, Object> body)
            throws Exception {
        return mvc.perform(as(post("/api/core/jobs/{id}/transitions", jobId), role, user, org)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role, UUID user, UUID org) {
        b.header("X-Dev-Role", role).header("X-Dev-User-Id", user.toString());
        if (org != null) {
            b.header("X-Dev-Org-Id", org.toString());
        }
        return b;
    }
}
