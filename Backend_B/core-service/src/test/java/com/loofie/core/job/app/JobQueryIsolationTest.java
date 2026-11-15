package com.loofie.core.job.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.loofie.core.common.ServiceBusyException;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobExceptions.InvalidQueryException;
import com.loofie.core.job.infra.JobReadRepository;
import com.loofie.core.job.read.JobViews.JobFilter;
import com.loofie.core.support.IntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The ops console's reads are fenced so they can never starve the engine (measured: unfenced, slow list
 * queries failed the whole service at 50 rps). docs/features/f5-job-engine.md plan items 1 and 3.
 */
@IntegrationTest
class JobQueryIsolationTest {

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired MeterRegistry meters;
    @Autowired JobQueryService queries;

    final Actor ops = Actor.ops(UUID.randomUUID());

    @Test
    void whenAllReadSlotsAreBusyTheNextReadIsTurnedAwayQuickly() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobReadRepository slow = new JobReadRepository(jdbc) {
            @Override
            public Optional<JobRow> findById(UUID jobId) {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
        // One slot, a short wait: like production's 5 slots, just easier to fill in a test.
        JobQueryService fenced = new JobQueryService(slow, Clock.systemUTC(), txManager, meters, 1, 1,
                Duration.ofMillis(200), Duration.ofSeconds(2));

        CompletableFuture<?> first = CompletableFuture.runAsync(() -> {
            try {
                fenced.detail(ops, UUID.randomUUID());
            } catch (RuntimeException expected) {
                // job not found once released
            }
        });
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> fenced.detail(ops, UUID.randomUUID())).isInstanceOf(ServiceBusyException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        // The slot is free again.
        assertThatThrownBy(() -> fenced.detail(ops, UUID.randomUUID()))
                .isNotInstanceOf(ServiceBusyException.class);
    }

    @Test
    void badCursorsAndPagesTooDeepAreRejected() {
        JobFilter all = new JobFilter(null, null, null, false);
        assertThatThrownBy(() -> queries.list(ops, all, 0, 25, "not-a-cursor"))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> queries.list(ops, all, 400, 25, null))
                .isInstanceOf(InvalidQueryException.class).hasMessageContaining("nextCursor");
    }
}
