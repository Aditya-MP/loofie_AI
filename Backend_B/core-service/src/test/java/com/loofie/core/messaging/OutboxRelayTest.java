package com.loofie.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.KafkaTestClient;
import com.loofie.core.support.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class OutboxRelayTest {

    @Autowired OutboxRelay relay;
    @Autowired OutboxMonitor monitor;
    @Autowired JobTransitionService engine;
    @Autowired JobFixtures fixtures;
    @Autowired KafkaTestClient kafka;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;

    /** Rows this test inserted on purpose with a broken topic; retired so they don't affect other tests. */
    @AfterEach
    void retireBrokenRows() {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE published_at IS NULL AND topic = 'missing.topic.v1'").update();
        // rows queued behind a broken one, so later tests start clean
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE published_at IS NULL AND message_key LIKE 'stuck-%'").update();
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
        assertThat(second.get("payload").has("reason")).as("ops notes stay inside the service").isFalse();
        assertThat(second.get("payload").get("hasReason").asBoolean()).isTrue();
        assertThat(new String(records.get(0).headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                .isEqualTo(first.get("eventId").asString());

        long unpublished = jdbc.sql("SELECT count(*) FROM outbox WHERE message_key = ? AND published_at IS NULL")
                .param(job.jobId().toString()).query(Long.class).single();
        assertThat(unpublished).isZero();
    }

    /** A cause and its automatic follow-up are written in the same millisecond; order must still hold. */
    @Test
    void automaticFollowUpsArePublishedAfterTheirCause() {
        List<UUID> jobIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Seeded seeded = fixtures.jobIn(JobStatus.PRODUCT_SHIPPED);
            jdbc.sql("""
                    INSERT INTO shipments (id, job_id, assignment_id, attempt, courier, tracking_number, shipped_at,
                                           shipped_by, version)
                    SELECT ?, ?, a.id, 1, 'Delhivery', 'DL1', now(), ?, 0 FROM assignments a
                    WHERE a.job_id = ? AND a.status = 'ACTIVE'
                    """).params(Ids.newId(), seeded.jobId(), UUID.randomUUID(), seeded.jobId()).update();
            engine.transition(TransitionCommand.of(seeded.jobId(), JobAction.CONFIRM_RECEIVED,
                    Actor.creator(seeded.creatorId()), null).with(TransitionDetails.photo(JobFixtures.photoKey())));
            jobIds.add(seeded.jobId());
        }

        drain();

        for (UUID jobId : jobIds) {
            List<ConsumerRecord<String, String>> records = kafka.read(Topics.CORE_JOB,
                    r -> jobId.toString().equals(r.key()), 2, Duration.ofSeconds(15));
            assertThat(records).extracting(r -> json.readTree(r.value()).get("payload").get("toState").asString())
                    .containsExactly("PRODUCT_RECEIVED", "IN_PRODUCTION");
        }
    }

    @Test
    void aFailingRowHoldsBackOnlyItsOwnKeyAndBacksOff() {
        String stuckKey = "stuck-" + UUID.randomUUID();
        String healthyKey = "healthy-" + UUID.randomUUID();
        UUID broken = insertRow("missing.topic.v1", stuckKey);
        UUID afterBroken = insertRow(Topics.CORE_JOB, stuckKey);
        UUID healthy = insertRow(Topics.CORE_JOB, healthyKey);

        drain();

        assertThat(isPublished(broken)).isFalse();
        assertThat(isPublished(afterBroken)).as("same key must wait for the failed row").isFalse();
        assertThat(isPublished(healthy)).as("other keys keep flowing").isTrue();

        var failure = jdbc.sql("SELECT attempts, last_error, next_attempt_at FROM outbox WHERE id = ?")
                .param(broken).query().singleRow();
        assertThat(failure.get("attempts")).as("backoff: not retried on every run").isEqualTo(1);
        assertThat((String) failure.get("last_error")).isNotBlank();
        assertThat(failure.get("next_attempt_at")).isNotNull();

        // Once the backoff has passed it is tried again (and fails again: the topic still does not exist).
        clock.advance(Duration.ofSeconds(2));
        drain();
        assertThat(attempts(broken)).isEqualTo(2);
        assertThat(isPublished(afterBroken)).isFalse();
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(OutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxRelay.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxRelay.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(OutboxRelay.backoff(7)).isEqualTo(OutboxRelay.MAX_BACKOFF);
        assertThat(OutboxRelay.backoff(500)).isEqualTo(OutboxRelay.MAX_BACKOFF);
    }

    @Test
    void theMonitorReportsTheBacklog() {
        drain();
        insertRow(Topics.CORE_JOB, "stuck-" + UUID.randomUUID()); // created a year ago, never published here
        monitor.refresh();
        assertThat(monitor.backlog()).isPositive();
        assertThat(monitor.oldestAgeSeconds()).isGreaterThan(Duration.ofDays(300).toSeconds());
    }

    @Test
    void nothingToPublishIsANoOp() {
        drain();
        assertThat(relay.publishBatch()).isZero();
    }

    /** Runs the relay until two runs in a row publish nothing (a run that stops on a broken row returns 0). */
    private void drain() {
        int idle = 0;
        for (int i = 0; i < 30 && idle < 2; i++) {
            idle = relay.publishBatch() > 0 ? 0 : idle + 1;
        }
    }

    private UUID insertRow(String topic, String key) {
        UUID id = Ids.newId();
        String envelope = json.writeValueAsString(new EventEnvelope(id, "TestEvent", 1, Instant.now(),
                EventEnvelope.PRODUCER, UUID.randomUUID(), 1, UUID.randomUUID(), null, Map.of()).toMap());
        jdbc.sql("""
                INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at)
                VALUES (?, ?, ?, 'TestEvent', CAST(? AS jsonb), ?)
                """)
                .params(id, topic, key, envelope, OffsetDateTime.now(ZoneOffset.UTC).minusYears(1))
                .update();
        return id;
    }

    private boolean isPublished(UUID id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?").param(id).query(Boolean.class).single();
    }

    private int attempts(UUID id) {
        return jdbc.sql("SELECT attempts FROM outbox WHERE id = ?").param(id).query(Integer.class).single();
    }
}
