package com.loofie.core.job.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

/** Retries, stale screens and request ids on the transition API (docs/production-readiness.md 1.1, 1.2, 5.1). */
@IntegrationTest
@AutoConfigureMockMvc
class TransitionApiHardeningTest {

    @Autowired MockMvc mvc;
    @Autowired JobFixtures fixtures;
    @Autowired JobRepository jobs;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    final UUID opsUser = UUID.randomUUID();

    @Test
    void aRetriedExtensionWithTheSameKeyExtendsOnlyOnce() throws Exception {
        UUID jobId = jobInProduction();
        Instant due = jobs.findById(jobId).orElseThrow().getProductionDueAt();
        String key = UUID.randomUUID().toString();

        mvc.perform(extendNoKey(jobId).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"));
        MvcResult retry = mvc.perform(extendNoKey(jobId).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(jobs.findById(jobId).orElseThrow().getProductionDueAt()).isEqualTo(due.plus(JobDeadlines.EXTENSION));
        assertThat(extensionEvents(jobId)).isEqualTo(1);
        assertThat(retry.getResponse().getContentAsString()).contains("\"to\":\"IN_PRODUCTION\"");
    }

    @Test
    void twentyParallelRetriesOfOneRequestActExactlyOnce() throws Exception {
        UUID jobId = jobInProduction();
        String key = UUID.randomUUID().toString();

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(() -> mvc.perform(extendNoKey(jobId).header("Idempotency-Key", key)).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
        }

        assertThat(statuses).containsOnly(200);
        assertThat(extensionEvents(jobId)).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() throws Exception {
        UUID jobId = jobInProduction();
        String key = UUID.randomUUID().toString();
        mvc.perform(extendNoKey(jobId).header("Idempotency-Key", key)).andExpect(status().isOk());

        mvc.perform(json(post("/api/core/jobs/{id}/transitions", jobId), """
                        {"action":"OPS_EXTEND","reason":"a different reason"}""").header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", is("https://loofie.app/problems/idempotency-key-reused")));
        assertThat(extensionEvents(jobId)).isEqualTo(1);
    }

    @Test
    void anExtensionWithoutAKeyIsRefusedBeforeItActs() throws Exception {
        UUID jobId = jobInProduction();
        mvc.perform(extendNoKey(jobId))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type", is("https://loofie.app/problems/idempotency-key-required")));
        assertThat(extensionEvents(jobId)).isZero();
    }

    @Test
    void anOutsiderCannotLearnAnythingAboutAJob() throws Exception {
        UUID jobId = jobInProduction();
        UUID missing = UUID.randomUUID();
        String otherBrand = UUID.randomUUID().toString();
        // Another brand: same answer as a job that does not exist, even with a stale version in the body
        // (which would otherwise reveal the current version) or an action illegal in this state.
        for (UUID id : new UUID[] {jobId, missing}) {
            mvc.perform(post("/api/core/jobs/{id}/transitions", id)
                            .header("X-Dev-Role", "BRAND_ADMIN").header("X-Dev-User-Id", UUID.randomUUID().toString())
                            .header("X-Dev-Org-Id", otherBrand).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\",\"expectedVersion\":0}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type", is("https://loofie.app/problems/job-not-found")));
        }
        mvc.perform(post("/api/core/jobs/{id}/transitions", jobId)
                        .header("X-Dev-Role", "CREATOR").header("X-Dev-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SUBMIT_DRAFT\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMalformedKeyIsRejected() throws Exception {
        UUID jobId = jobInProduction();
        mvc.perform(extendNoKey(jobId).header("Idempotency-Key", "has spaces in it"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", is("https://loofie.app/problems/invalid-idempotency-key")));
    }

    @Test
    void actingOnAStaleScreenIsRefused() throws Exception {
        UUID jobId = jobInProduction();
        long shown = jobs.findById(jobId).orElseThrow().getVersion();
        mvc.perform(extend(jobId)).andExpect(status().isOk()); // someone else acts first

        mvc.perform(json(post("/api/core/jobs/{id}/transitions", jobId),
                        "{\"action\":\"OPS_EXTEND\",\"reason\":\"creator asked\",\"expectedVersion\":" + shown + "}")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", is("https://loofie.app/problems/stale-version")))
                .andExpect(jsonPath("$.currentVersion", is((int) shown + 1)));
        assertThat(extensionEvents(jobId)).isEqualTo(1);
    }

    @Test
    void theRequestIdIsEchoedPutOnErrorsAndUsedAsTheCorrelationId() throws Exception {
        UUID jobId = jobInProduction();
        UUID requestId = UUID.randomUUID();

        mvc.perform(extend(jobId).header("X-Request-Id", requestId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", requestId.toString()));
        String correlation = jdbc.sql("""
                SELECT envelope ->> 'correlationId' FROM outbox
                WHERE message_key = ? AND event_type = 'JobStateChanged' ORDER BY seq DESC LIMIT 1
                """).param(jobId.toString()).query(String.class).single();
        assertThat(correlation).isEqualTo(requestId.toString());

        mvc.perform(json(post("/api/core/jobs/{id}/transitions", jobId), "{\"action\":\"APPROVE\",\"reason\":\"x\"}")
                        .header("X-Request-Id", requestId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.requestId", is(requestId.toString())));
    }

    @Test
    void aNonUuidRequestIdIsReplacedNotLogged() throws Exception {
        UUID jobId = jobInProduction();
        mvc.perform(extend(jobId).header("X-Request-Id", "evil\nfake log line"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", notNullValue()))
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Request-Id")).hasSize(36));
    }

    @Test
    void theOpsNoteNeverLeavesTheService() throws Exception {
        UUID jobId = jobInProduction();
        mvc.perform(json(post("/api/core/jobs/{id}/transitions", jobId),
                "{\"action\":\"OPS_EXTEND\",\"reason\":\"creator's phone is 98765 43210\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());

        String envelope = jdbc.sql("SELECT envelope::text FROM outbox WHERE message_key = ? ORDER BY seq DESC LIMIT 1")
                .param(jobId.toString()).query(String.class).single();
        assertThat(envelope).doesNotContain("98765").contains("\"hasReason\": true");
        String kept = jdbc.sql("SELECT reason FROM job_events WHERE job_id = ? ORDER BY job_version DESC NULLS LAST LIMIT 1")
                .param(jobId).query(String.class).single();
        assertThat(kept).contains("98765"); // still in the internal audit log
    }

    // ---- helpers ----

    private UUID jobInProduction() {
        Seeded seeded = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        fixtures.setDeadline(seeded.jobId(), "production_due_at", clock.instant().plus(JobDeadlines.PRODUCTION_WINDOW));
        return seeded.jobId();
    }

    /** An ops extension with a fresh Idempotency-Key (required for OPS_EXTEND). */
    private MockHttpServletRequestBuilder extend(UUID jobId) {
        return extendNoKey(jobId).header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder extendNoKey(UUID jobId) {
        return json(post("/api/core/jobs/{id}/transitions", jobId), "{\"action\":\"OPS_EXTEND\",\"reason\":\"creator asked\"}");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.header("X-Dev-Role", "OPS").header("X-Dev-User-Id", opsUser.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long extensionEvents(UUID jobId) {
        return jdbc.sql("SELECT count(*) FROM job_events WHERE job_id = ? AND action = 'OPS_EXTEND'")
                .param(jobId).query(Long.class).single();
    }
}
