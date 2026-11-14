package com.loofie.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.common.Ids;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.KafkaTestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class OutboxRelayTest {

    @Autowired OutboxRelay relay;
    @Autowired JobTransitionService engine;
    @Autowired JobFixtures fixtures;
    @Autowired KafkaTestClient kafka;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    /** Rows this test inserted on purpose with a broken topic; retired so they don't affect other tests. */
    @AfterEach
    void retireBrokenRows() {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE published_at IS NULL AND topic = 'missing.topic.v1'").update();
    }

    @Test
    void publishesStateChangesInOrderWithEnvelopeAndMarksThemPublished() {
        Seeded job = fixtures.jobIn(JobStatus.AWAITING_PAYMENT);
        engine.transition(job.jobId(), JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        engine.transition(job.jobId(), JobAction.CANCEL, Actor.ops(UUID.randomUUID()), "brand withdrew");

        drain();

        List<ConsumerRecord<String, String>> records = kafka.read(Topics.CORE_JOB,
                r -> job.jobId().toString().equals(r.key()), 2, Duration.ofSeconds(15));
        assertThat(records).hasSize(2);

        JsonNode first = json.readTree(records.get(0).value());
        JsonNode second = json.readTree(records.get(1).value());
        assertThat(first.get("payload").get("toState").asString()).isEqualTo("PAID");
        assertThat(second.get("payload").get("toState").asString()).isEqualTo("CANCELLED");
        assertThat(first.get("eventType").asString()).isEqualTo("JobStateChanged");
        assertThat(first.get("producer").asString()).isEqualTo("core-service");
        assertThat(first.get("aggregateVersion").asLong()).isLessThan(second.get("aggregateVersion").asLong());
        assertThat(second.get("causationId").isNull()).isTrue();
        assertThat(new String(records.get(0).headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                .isEqualTo(first.get("eventId").asString());

        long unpublished = jdbc.sql("SELECT count(*) FROM outbox WHERE message_key = ? AND published_at IS NULL")
                .param(job.jobId().toString()).query(Long.class).single();
        assertThat(unpublished).isZero();
    }

    @Test
    void aFailingRowHoldsBackOnlyItsOwnKey() {
        String stuckKey = "stuck-" + UUID.randomUUID();
        String healthyKey = "healthy-" + UUID.randomUUID();
        UUID broken = insertRow("missing.topic.v1", stuckKey, 1);
        UUID afterBroken = insertRow(Topics.CORE_JOB, stuckKey, 2);
        UUID healthy = insertRow(Topics.CORE_JOB, healthyKey, 3);

        relay.publishBatch();

        assertThat(isPublished(broken)).isFalse();
        assertThat(isPublished(afterBroken)).as("same key must wait for the failed row").isFalse();
        assertThat(isPublished(healthy)).as("other keys keep flowing").isTrue();

        var failure = jdbc.sql("SELECT attempts, last_error FROM outbox WHERE id = ?").param(broken).query().singleRow();
        assertThat(failure.get("attempts")).isEqualTo(1);
        assertThat((String) failure.get("last_error")).isNotBlank();
    }

    @Test
    void nothingToPublishIsANoOp() {
        drain();
        assertThat(relay.publishBatch()).isZero();
    }

    private void drain() {
        for (int i = 0; i < 20 && relay.publishBatch() > 0; i++) {
            // keep going until the backlog is empty
        }
    }

    private UUID insertRow(String topic, String key, int secondsOffset) {
        UUID id = Ids.newId();
        String envelope = json.writeValueAsString(new EventEnvelope(id, "TestEvent", 1, Instant.now(),
                EventEnvelope.PRODUCER, UUID.randomUUID(), 1, UUID.randomUUID(), null, Map.of()).toMap());
        // created a year ago so these rows sort first in the batch
        jdbc.sql("""
                INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at)
                VALUES (?, ?, ?, 'TestEvent', CAST(? AS jsonb), ?)
                """)
                .params(id, topic, key, envelope, OffsetDateTime.now(ZoneOffset.UTC).minusYears(1).plusSeconds(secondsOffset))
                .update();
        return id;
    }

    private boolean isPublished(UUID id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?").param(id).query(Boolean.class).single();
    }
}
