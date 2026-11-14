package com.loofie.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.support.IntegrationTest;

/** Two "instances" competing for one background job (docs/production-readiness.md 2.5). */
@IntegrationTest
class LeaderLeaseTest {

    @Autowired JdbcClient jdbc;

    @Test
    void onlyOneInstanceHoldsALeaseUntilItExpiresOrIsReleased() {
        String name = "test-" + UUID.randomUUID();
        LeaderLease a = new LeaderLease(jdbc, "instance-a");
        LeaderLease b = new LeaderLease(jdbc, "instance-b");

        assertThat(a.tryAcquire(name, Duration.ofMinutes(1))).isTrue();
        assertThat(b.tryAcquire(name, Duration.ofMinutes(1))).as("held by a").isFalse();
        assertThat(a.tryAcquire(name, Duration.ofMinutes(1))).as("the holder renews").isTrue();

        // a crashes: its lease runs out and b takes over
        jdbc.sql("UPDATE scheduler_leases SET expires_at = now() - interval '1 second' WHERE name = ?").param(name).update();
        assertThat(b.tryAcquire(name, Duration.ofMinutes(1))).isTrue();
        assertThat(a.tryAcquire(name, Duration.ofMinutes(1))).as("a lost it").isFalse();

        // b shuts down cleanly: a can take over at once
        b.releaseAll();
        assertThat(a.tryAcquire(name, Duration.ofMinutes(1))).isTrue();
    }
}
