package com.loofie.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.common.Ids;
import com.loofie.core.common.LeaderLease;
import com.loofie.core.job.app.JobNoticeService;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.FakeAiService;
import com.loofie.core.support.FakeAiService.Received;
import com.loofie.core.support.FakeAiService.Reply;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** The outbox reaches Backend A's /ai/feedback over HTTP (docs/features/kafka-removal.md, tests 1-12). */
@IntegrationTest
class OutboxRelayTest {

    @Autowired OutboxRelay relay;
    @Autowired OutboxMonitor monitor;
    @Autowired EventSender sender;
    @Autowired AiFeedbackMapper mapper;
    @Autowired LeaderLease lease;
    @Autowired JobTransitionService engine;
    @Autowired JobNoticeService notices;
    @Autowired JobFixtures fixtures;
    @Autowired FakeAiService ai;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;

    @AfterEach
    void cleanUp() {
        ai.reset();
        // rows this class left failing on purpose, so later tests start clean
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE published_at IS NULL AND message_key LIKE 'stuck-%'").update();
    }

    // 1
    @Test
    void aStateChangeReachesBackendAOnceInTheAgreedShape() {
        Seeded job = shippedJob();
        engine.transition(TransitionCommand.of(job.jobId(), JobAction.CONFIRM_RECEIVED, Actor.creator(job.creatorId()),
                null).with(TransitionDetails.photo(JobFixtures.photoKey())));

        drain();

        List<Received> sent = ai.forJob(job.jobId().toString());
        assertThat(sent).extracting(r -> r.payload().get("to").asString())
                .containsExactly("PRODUCT_RECEIVED", "IN_PRODUCTION");
        Received first = sent.getFirst();
        assertThat(first.apiKey()).isEqualTo(FakeAiService.API_KEY);
        assertThat(first.requestId()).isEqualTo(first.eventId());
        assertThat(first.body().get("event_type").asString()).isEqualTo("job_state_changed");
        assertThat(first.body().get("actor_type").asString()).isEqualTo("creator");
        assertThat(first.body().get("subject_type").asString()).isEqualTo("job");
        assertThat(first.body().get("subject_id").asString()).isEqualTo(job.jobId().toString());
        assertThat(first.body().get("occurred_at").asString()).endsWith("Z");
        assertThat(first.payload().get("creator_ref").asString()).isEqualTo(job.creatorId().toString());
        assertThat(first.payload().get("from").asString()).isEqualTo("PRODUCT_SHIPPED");
        assertThat(first.payload().get("shipmentAttempt").asLong()).isEqualTo(1);
        // the same id as the audit log, so a retry is recognised as one
        UUID logged = jdbc.sql("""
                SELECT event_id FROM job_events WHERE job_id = ? AND to_state = 'PRODUCT_RECEIVED'
                """).param(job.jobId()).query(UUID.class).single();
        assertThat(first.eventId()).isEqualTo(logged.toString());
        assertThat(sent).allSatisfy(r -> assertThat(ai.calls(r.eventId())).as("sent once").isEqualTo(1));
        assertThat(unpublished(job.jobId().toString())).isZero();
    }

    /** A cause and its automatic follow-up are written in the same millisecond; order must still hold. */
    @Test
    void automaticFollowUpsArriveAfterTheirCause() {
        List<UUID> jobIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Seeded seeded = shippedJob();
            engine.transition(TransitionCommand.of(seeded.jobId(), JobAction.CONFIRM_RECEIVED,
                    Actor.creator(seeded.creatorId()), null).with(TransitionDetails.photo(JobFixtures.photoKey())));
            jobIds.add(seeded.jobId());
        }

        drain();

        for (UUID jobId : jobIds) {
            assertThat(ai.forJob(jobId.toString())).extracting(r -> r.payload().get("to").asString())
                    .containsExactly("PRODUCT_RECEIVED", "IN_PRODUCTION");
        }
    }

    // 2
    @Test
    void aTimerNoticeBecomesJobTimerFired() {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        Instant dueAt = clock.instant().plus(Duration.ofHours(6));
        notices.raise(new JobNoticeService.Notice(job.jobId(), job.orderId(), 1, JobStatus.CREATOR_ACCEPTED,
                "SHIP_REMINDER", dueAt, List.of("BRAND")), clock.instant());

        drain();

        List<Received> sent = ai.forJob(job.jobId().toString());
        assertThat(sent).hasSize(1);
        Received r = sent.getFirst();
        assertThat(r.body().get("event_type").asString()).isEqualTo("job_timer_fired");
        assertThat(r.body().get("actor_type").asString()).isEqualTo("system");
        assertThat(r.payload().get("creator_ref").asString()).isEqualTo(job.creatorId().toString());
        assertThat(r.payload().get("kind").asString()).isEqualTo("SHIP_REMINDER");
        assertThat(r.payload().get("due_at").asString()).isEqualTo(dueAt.toString());
        assertThat(r.payload().get("audience").get(0).asString()).isEqualTo("BRAND");
    }

    // 3
    @Test
    void eventsWithoutACreatorAreMarkedDoneButNeverSent() {
        Seeded job = fixtures.jobIn(JobStatus.AWAITING_PAYMENT);
        engine.transition(job.jobId(), JobAction.PAYMENT_CONFIRMED, Actor.system(), null);

        drain();

        assertThat(ai.forJob(job.jobId().toString())).isEmpty();
        assertThat(unpublished(job.jobId().toString())).as("done, not waiting forever").isZero();
    }

    // 4
    @Test
    void opsNotesAndInternalIdsNeverLeaveTheService() {
        Seeded job = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        engine.transition(job.jobId(), JobAction.CANCEL, Actor.ops(UUID.randomUUID()),
                "brand withdrew, call Priya on 98765 43210");

        drain();

        List<Received> sent = ai.forJob(job.jobId().toString());
        assertThat(sent).hasSize(1);
        String body = sent.getFirst().body().toString();
        assertThat(body).doesNotContain("Priya", "98765", "withdrew");
        assertThat(sent.getFirst().payload().propertyNames())
                .doesNotContain("reason", "hasReason", "actorId", "orderId", "assignmentId");
        assertThat(sent.getFirst().payload().get("creator_ref").asString())
                .as("the creator of the assignment that was just closed").isEqualTo(job.creatorId().toString());
    }

    // 5
    @Test
    void aDuplicateAnswerCountsAsDelivered() {
        UUID row = insertRow("stuck-" + UUID.randomUUID());
        ai.script(row.toString(), call -> Reply.ok(row.toString(), true));

        drain();

        assertThat(isPublished(row)).isTrue();
    }

    @Test
    void aConflictMeansBackendAAlreadyHasItSoItCountsAsDelivered() {
        UUID row = insertRow("stuck-" + UUID.randomUUID());
        ai.script(row.toString(), call -> new Reply(409, "{\"detail\":\"event_id already used\"}"));

        drain();

        assertThat(isPublished(row)).isTrue();
        assertThat(ai.calls(row.toString())).isEqualTo(1);
    }

    // 6
    @Test
    void aRejectedRowIsParkedAndTheJobsNextEventStillGoesOut() {
        String key = "stuck-" + UUID.randomUUID();
        UUID bad = insertRow(key);
        UUID next = insertRow(key);
        ai.script(bad.toString(), call -> new Reply(422, "{\"detail\":\"job_state_changed needs payload keys: [to]\"}"));

        drain();

        var parked = jdbc.sql("SELECT parked_at, published_at, last_error FROM outbox WHERE id = ?").param(bad)
                .query().singleRow();
        assertThat(parked.get("parked_at")).isNotNull();
        assertThat(parked.get("published_at")).isNull();
        assertThat((String) parked.get("last_error")).contains("422", "needs payload keys");
        assertThat(ai.calls(bad.toString())).as("never retried").isEqualTo(1);
        assertThat(isPublished(next)).as("does not block the job").isTrue();
        monitor.refresh();
        assertThat(monitor.parked()).isPositive();
    }

    // 7
    @Test
    void aServerErrorBacksOffThenDeliversInOrder() {
        String key = "stuck-" + UUID.randomUUID();
        UUID first = insertRow(key);
        UUID second = insertRow(key);
        ai.script(first.toString(), call -> call == 1 ? new Reply(503, "{}") : Reply.ok(first.toString(), false));

        drain();
        assertThat(isPublished(first)).isFalse();
        assertThat(ai.calls(second.toString())).as("waits for the failed row").isZero();
        assertThat(attempts(first)).as("backoff: not retried on every run").isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        drain();

        assertThat(isPublished(first)).isTrue();
        assertThat(isPublished(second)).isTrue();
        List<String> order = ai.received().stream().map(Received::eventId)
                .filter(id -> id.equals(first.toString()) || id.equals(second.toString())).toList();
        assertThat(order).containsExactly(first.toString(), first.toString(), second.toString());
    }

    // 9
    @Test
    void aWrongApiKeyIsRetriedNotParked() {
        UUID row = insertRow("stuck-" + UUID.randomUUID());
        ai.script(row.toString(), call -> new Reply(401, "{\"detail\":\"bad key\"}"));

        drain();

        var r = jdbc.sql("SELECT parked_at, next_attempt_at, last_error FROM outbox WHERE id = ?").param(row)
                .query().singleRow();
        assertThat(r.get("parked_at")).as("fixing the key fixes it").isNull();
        assertThat(r.get("next_attempt_at")).isNotNull();
        assertThat((String) r.get("last_error")).contains("401");
    }

    // 8 (was "broker down")
    @Test
    void anUnreachableBackendAStopsTheRunQuicklyAndNothingIsLost() {
        drain();
        UUID row = insertRow("stuck-" + UUID.randomUUID());
        List<UUID> alreadyBackingOff = backingOff();
        // Nothing listens on port 1: every connection is refused.
        EventSender dead = new AiFeedbackSender(new AiFeedbackProperties("http://localhost:1", "k",
                Duration.ofSeconds(1), Duration.ofSeconds(1)), mapper, json);
        OutboxRelay deadRelay = new OutboxRelay(jdbc, dead,
                new OutboxProperties(false, Duration.ofMillis(500), 100, Duration.ofSeconds(2), 16), clock,
                new SimpleMeterRegistry());
        try {
            long start = System.nanoTime();
            int done = deadRelay.publishBatch();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(done).isZero();
            assertThat(took).isLessThan(Duration.ofSeconds(3));
            assertThat(isPublished(row)).isFalse();
            assertThat(backingOff()).as("put on backoff, not retried in a hot loop").contains(row);
        } finally {
            List<UUID> fresh = backingOff();
            fresh.removeAll(alreadyBackingOff);
            fresh.remove(row);
            for (UUID id : fresh) { // other tests' rows the dead relay touched: as they were
                jdbc.sql("UPDATE outbox SET next_attempt_at = NULL, attempts = 0, last_error = NULL WHERE id = ?")
                        .param(id).update();
            }
        }
    }

    // 10
    @Test
    void neverMoreThanMaxInFlightRequestsAtOnce() {
        drain();
        List<UUID> rows = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            rows.add(insertRow("stuck-" + UUID.randomUUID()));
        }
        ai.reset();
        ai.delay(Duration.ofMillis(50));
        OutboxRelay capped = new OutboxRelay(jdbc, sender,
                new OutboxProperties(false, Duration.ofMillis(500), 100, Duration.ofSeconds(5), 4), clock,
                new SimpleMeterRegistry());

        capped.publishBatch();

        assertThat(rows).allSatisfy(id -> assertThat(isPublished(id)).isTrue());
        assertThat(ai.maxInFlight()).isBetween(2, 4);
    }

    // 11
    @Test
    void rowsWrittenBeforeBackendAIsConfiguredAreSentOnceItIs() {
        drain();
        UUID row = insertRow("stuck-" + UUID.randomUUID());
        EventSender notConfigured = new AiFeedbackSender(new AiFeedbackProperties("", "", null, null), mapper, json);
        OutboxRelay idleRelay = new OutboxRelay(jdbc, notConfigured,
                new OutboxProperties(false, null, 0, null, 0), clock, new SimpleMeterRegistry());
        new OutboxRelayScheduler(idleRelay, notConfigured, lease, new OutboxProperties(false, null, 0, null, 0)).run();

        assertThat(isPublished(row)).as("waits, is not dropped").isFalse();
        assertThat(ai.calls(row.toString())).isZero();

        drain(); // configured now
        assertThat(isPublished(row)).isTrue();
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
        UUID row = insertRow("stuck-" + UUID.randomUUID()); // created a year ago
        jdbc.sql("UPDATE outbox SET next_attempt_at = now() + interval '1 hour' WHERE id = ?").param(row).update();
        monitor.refresh();
        assertThat(monitor.backlog()).isPositive();
        assertThat(monitor.oldestAgeSeconds()).isGreaterThan(Duration.ofDays(300).toSeconds());
    }

    @Test
    void nothingToSendIsANoOp() {
        drain();
        assertThat(relay.publishBatch()).isZero();
    }

    /** Runs the relay until two runs in a row finish nothing (a run that stops on a failing row returns 0). */
    private void drain() {
        int idle = 0;
        for (int i = 0; i < 30 && idle < 2; i++) {
            idle = relay.publishBatch() > 0 ? 0 : idle + 1;
        }
    }

    private Seeded shippedJob() {
        Seeded seeded = fixtures.jobIn(JobStatus.PRODUCT_SHIPPED);
        jdbc.sql("""
                INSERT INTO shipments (id, job_id, assignment_id, attempt, courier, tracking_number, shipped_at,
                                       shipped_by, version)
                SELECT ?, ?, a.id, 1, 'Delhivery', 'DL1', now(), ?, 0 FROM assignments a
                WHERE a.job_id = ? AND a.status = 'ACTIVE'
                """).params(Ids.newId(), seeded.jobId(), UUID.randomUUID(), seeded.jobId()).update();
        return seeded;
    }

    /** A JobStateChanged row with a creator, created a year ago. */
    private UUID insertRow(String key) {
        UUID id = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", key);
        payload.put("creatorId", UUID.randomUUID().toString());
        payload.put("fromState", "IN_PRODUCTION");
        payload.put("toState", "IN_REVIEW");
        payload.put("actorType", "CREATOR");
        String envelope = json.writeValueAsString(new EventEnvelope(id, AiFeedbackMapper.STATE_CHANGED, 1,
                Instant.now(), EventEnvelope.PRODUCER, UUID.randomUUID(), 1, UUID.randomUUID(), null, payload).toMap());
        jdbc.sql("""
                INSERT INTO outbox (id, topic, message_key, event_type, envelope, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """)
                .params(id, Outbox.JOB_STREAM, key, AiFeedbackMapper.STATE_CHANGED, envelope,
                        OffsetDateTime.now(ZoneOffset.UTC).minusYears(1))
                .update();
        return id;
    }

    private boolean isPublished(UUID id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?").param(id).query(Boolean.class).single();
    }

    private int attempts(UUID id) {
        return jdbc.sql("SELECT attempts FROM outbox WHERE id = ?").param(id).query(Integer.class).single();
    }

    private long unpublished(String key) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE message_key = ? AND published_at IS NULL").param(key)
                .query(Long.class).single();
    }

    private List<UUID> backingOff() {
        return new ArrayList<>(jdbc.sql(
                "SELECT id FROM outbox WHERE published_at IS NULL AND next_attempt_at IS NOT NULL")
                .query(UUID.class).list());
    }
}
