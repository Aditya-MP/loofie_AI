package com.loofie.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.loofie.core.common.Ids;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.MutableClock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka completely unreachable (not just a missing topic): the relay must fail fast, publish nothing,
 * lose nothing, and back off. docs/features/f5-job-engine.md plan item 9.
 */
@IntegrationTest
class BrokerDownTest {

    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;
    @Autowired JsonMapper json;

    @Test
    void aDeadBrokerStopsTheRunQuicklyAndNothingIsLostOrMarkedSent() {
        String key = "deadbroker-" + UUID.randomUUID();
        UUID row = Ids.newId();
        String envelope = json.writeValueAsString(new EventEnvelope(row, "TestEvent", 1, Instant.now(),
                EventEnvelope.PRODUCER, UUID.randomUUID(), 1, UUID.randomUUID(), null, Map.of()).toMap());
        jdbc.sql("""
                INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at)
                VALUES (?, 'core.job.v1', ?, 'TestEvent', CAST(? AS jsonb), ?)
                """).params(row, key, envelope, OffsetDateTime.now(ZoneOffset.UTC)).update();
        List<UUID> alreadyBackingOff = backingOff();
        long publishedBefore = publishedCount();

        // Nothing listens on port 1: every connection is refused.
        KafkaTemplate<String, String> dead = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1500)));
        OutboxRelay relay = new OutboxRelay(jdbc, dead,
                new OutboxProperties(false, Duration.ofMillis(500), 100, Duration.ofSeconds(2)), clock,
                new SimpleMeterRegistry());
        try {
            long start = System.nanoTime();
            int sent = relay.publishBatch();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(sent).isZero();
            // One failed send, then the run stops: not 100 rows x the timeout.
            assertThat(took).isLessThan(Duration.ofSeconds(5));
            assertThat(publishedCount()).as("nothing is marked as sent").isEqualTo(publishedBefore);
            List<UUID> nowBackingOff = backingOff();
            assertThat(nowBackingOff.size()).as("the failed row is put on backoff, not retried in a hot loop")
                    .isGreaterThan(alreadyBackingOff.size());
        } finally {
            dead.destroy();
            // Leave other tests' rows as they were; retire this test's row.
            List<UUID> fresh = backingOff();
            fresh.removeAll(alreadyBackingOff);
            for (UUID id : fresh) {
                jdbc.sql("UPDATE outbox SET next_attempt_at = NULL, attempts = 0, last_error = NULL WHERE id = ?")
                        .param(id).update();
            }
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ?").param(row).update();
        }
    }

    private List<UUID> backingOff() {
        return new java.util.ArrayList<>(jdbc.sql(
                "SELECT id FROM outbox WHERE published_at IS NULL AND next_attempt_at IS NOT NULL")
                .query(UUID.class).list());
    }

    private long publishedCount() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL").query(Long.class).single();
    }
}
