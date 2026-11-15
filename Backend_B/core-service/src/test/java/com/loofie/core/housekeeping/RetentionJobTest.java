package com.loofie.core.housekeeping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.common.Ids;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.MutableClock;

/** Old rows are deleted; anything still needed is kept (docs/production-readiness.md 2.6, 2.7). */
@IntegrationTest
class RetentionJobTest {

    @Autowired RetentionJob retention;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    @Test
    void deletesOnlyRowsPastTheirRetention() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        UUID oldPublished = outbox(now.minusDays(8), now.minusDays(8));
        UUID recentPublished = outbox(now.minusDays(1), now.minusDays(1));
        UUID oldUnpublished = outbox(now.minusDays(30), null); // never delete what Backend A has not got
        UUID oldParked = parked(now.minusDays(31));
        UUID recentParked = parked(now.minusDays(2)); // kept for investigation and requeue
        String oldKey = key(now.minusHours(25));
        String recentKey = key(now.minusHours(1));

        retention.runOnce();

        assertThat(exists("SELECT count(*) FROM outbox WHERE id = ?", oldPublished)).isFalse();
        assertThat(exists("SELECT count(*) FROM outbox WHERE id = ?", recentPublished)).isTrue();
        assertThat(exists("SELECT count(*) FROM outbox WHERE id = ?", oldUnpublished)).isTrue();
        assertThat(exists("SELECT count(*) FROM outbox WHERE id = ?", oldParked)).isFalse();
        assertThat(exists("SELECT count(*) FROM outbox WHERE id = ?", recentParked)).isTrue();
        assertThat(exists("SELECT count(*) FROM idempotency_keys WHERE idem_key = ?", oldKey)).isFalse();
        assertThat(exists("SELECT count(*) FROM idempotency_keys WHERE idem_key = ?", recentKey)).isTrue();

        // so it does not sit in other tests' backlog
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ?").param(oldUnpublished).update();
    }

    private UUID outbox(OffsetDateTime createdAt, OffsetDateTime publishedAt) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at, published_at)
                VALUES (?, 'retention.test', ?, 'TestEvent', '{}'::jsonb, ?, ?)
                """).params(id, "retention-" + id, createdAt, publishedAt).update();
        return id;
    }

    private UUID parked(OffsetDateTime parkedAt) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at, parked_at, last_error)
                VALUES (?, 'retention.test', ?, 'TestEvent', '{}'::jsonb, ?, ?, 'Rejected: HTTP 422')
                """).params(id, "retention-" + id, parkedAt, parkedAt).update();
        return id;
    }

    private String key(OffsetDateTime createdAt) {
        String key = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO idempotency_keys (scope, idem_key, request_hash, response, created_at)
                VALUES ('OPS:test', ?, repeat('0', 64), '{}'::jsonb, ?)
                """).params(key, createdAt).update();
        return key;
    }

    private boolean exists(String sql, Object id) {
        return jdbc.sql(sql).param(id).query(Long.class).single() > 0;
    }
}
