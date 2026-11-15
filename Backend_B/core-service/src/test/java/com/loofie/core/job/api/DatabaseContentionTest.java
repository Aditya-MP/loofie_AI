package com.loofie.core.job.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.MutableClock;

/**
 * The database cannot serve a request in time (a row stays locked past lock_timeout): the client gets a
 * 503 with Retry-After, nothing is half-written, and retrying with the same Idempotency-Key works once the
 * database is free. docs/features/f5-job-engine.md plan item 9. Takes about 5 seconds (the lock timeout).
 */
@IntegrationTest
@AutoConfigureMockMvc
class DatabaseContentionTest {

    @Autowired MockMvc mvc;
    @Autowired JobFixtures fixtures;
    @Autowired JobRepository jobs;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager txManager;

    final UUID opsUser = UUID.randomUUID(); // keys are per caller, so the retry must come from the same user

    @Test
    void aLockedRowGivesA503AndARetryWithTheSameKeySucceedsLater() throws Exception {
        UUID jobId = fixtures.jobIn(JobStatus.IN_PRODUCTION).jobId();
        Instant due = clock.instant().plus(JobDeadlines.PRODUCTION_WINDOW);
        fixtures.setDeadline(jobId, "production_due_at", due);
        String key = UUID.randomUUID().toString();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                new TransactionTemplate(txManager).executeWithoutResult(s -> {
                    jdbc.sql("SELECT id FROM jobs WHERE id = ? FOR UPDATE").param(jobId).query().listOfRows();
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            mvc.perform(extend(jobId, key))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.type", is("https://loofie.app/problems/temporarily-unavailable")));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        // Nothing was half-written: same deadline, no event, and the key was not consumed.
        assertThat(jobs.findById(jobId).orElseThrow().getProductionDueAt()).isEqualTo(due);
        assertThat(jdbc.sql("SELECT count(*) FROM job_events WHERE job_id = ?").param(jobId).query(Long.class).single())
                .isZero();

        mvc.perform(extend(jobId, key)).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"));
        assertThat(jobs.findById(jobId).orElseThrow().getProductionDueAt()).isEqualTo(due.plus(JobDeadlines.EXTENSION));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder extend(UUID jobId, String key) {
        return post("/api/core/jobs/{id}/transitions", jobId)
                .header("X-Dev-Role", "OPS").header("X-Dev-User-Id", opsUser.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"OPS_EXTEND\",\"reason\":\"creator asked\"}");
    }
}
