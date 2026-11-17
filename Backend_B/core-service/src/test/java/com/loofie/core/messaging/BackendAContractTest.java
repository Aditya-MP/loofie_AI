package com.loofie.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.common.Ids;
import com.loofie.core.job.app.JobNoticeService;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.DeliverySource;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.ReasonCode;
import com.loofie.core.messaging.EventSender.Delivery;
import com.loofie.core.messaging.EventSender.Message;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.JobFixtures.Seeded;
import com.loofie.core.support.MutableClock;

import tools.jackson.databind.json.JsonMapper;

/**
 * Contract check against a real Backend A (their mock applies the same /ai/feedback rules as the live service).
 * Off unless AI_CONTRACT_URL is set:
 * <pre>
 *   AI_CONTRACT_URL=http://127.0.0.1:8100 AI_CONTRACT_KEY=... ./mvnw test -Dtest=BackendAContractTest
 * </pre>
 * Every kind of event Core produces is sent; every one must be accepted, and a resend must be a duplicate
 * (not a 409: our mapping is stable).
 */
@IntegrationTest
@EnabledIfEnvironmentVariable(named = "AI_CONTRACT_URL", matches = ".+")
class BackendAContractTest {

    @Autowired JobTransitionService engine;
    @Autowired JobNoticeService notices;
    @Autowired com.loofie.core.refund.app.RefundService refunds;
    @Autowired JobFixtures fixtures;
    @Autowired AiFeedbackMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;

    @Test
    void everyEventCoreProducesIsAcceptedByBackendA() throws Exception {
        Instant before = clock.instant();
        clock.set(Instant.now()); // Backend A refuses events more than 5 minutes in its future
        try {
            sendEveryKindOfEvent();
        } finally {
            clock.set(before);
        }
    }

    private void sendEveryKindOfEvent() throws Exception {
        Actor ops = Actor.ops(UUID.randomUUID());
        List<UUID> jobs = new ArrayList<>();

        // Delivery problem: courier proof, issue reported, ops releases the creator with a reason code.
        Seeded a = shipped();
        engine.transition(TransitionCommand.of(a.jobId(), JobAction.COURIER_DELIVERED, ops, "tracking says delivered")
                .with(TransitionDetails.courierDelivered(DeliverySource.TRACKING_PAGE, null)));
        engine.transition(TransitionCommand.of(a.jobId(), JobAction.REPORT_DELIVERY_ISSUE, Actor.creator(a.creatorId()),
                null).with(TransitionDetails.issue(DeliveryIssueType.WRONG_ITEM)));
        engine.transition(TransitionCommand.of(a.jobId(), JobAction.OPS_RELEASE_CREATOR, ops, "brand sent wrong SKU")
                .with(TransitionDetails.reason(ReasonCode.WRONG_ITEM_SENT)));
        jobs.add(a.jobId());

        // Happy path: receipt with a photo, then the automatic move into production.
        Seeded b = shipped();
        engine.transition(TransitionCommand.of(b.jobId(), JobAction.CONFIRM_RECEIVED, Actor.creator(b.creatorId()), null)
                .with(TransitionDetails.received(JobFixtures.photoKey(), 1)));
        jobs.add(b.jobId());

        // A reminder, then ops cancels with a free-text note.
        Seeded c = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
        Instant dueAt = clock.instant().plus(Duration.ofHours(6));
        notices.raise(new JobNoticeService.Notice(c.jobId(), c.orderId(), 1, JobStatus.CREATOR_ACCEPTED,
                "SHIP_REMINDER", dueAt, List.of("BRAND")), clock.instant());
        var cancel = refunds.request(ops, c.jobId(), JobAction.CANCEL,
                com.loofie.core.refund.domain.RefundReason.BRAND_CANCELLED, "brand withdrew, call Priya on 98765 43210");
        refunds.approve(Actor.opsLead(UUID.randomUUID()), cancel.id(), null);
        jobs.add(c.jobId());

        // F6 round 2: damage found after receipt (clock paused), then ops releases with DEFECTIVE_PRODUCT.
        Seeded d = shipped();
        engine.transition(TransitionCommand.of(d.jobId(), JobAction.CONFIRM_RECEIVED, Actor.creator(d.creatorId()), null)
                .with(TransitionDetails.photo(JobFixtures.photoKey())));
        engine.transition(TransitionCommand.of(d.jobId(), JobAction.REPORT_DELIVERY_ISSUE, Actor.creator(d.creatorId()),
                null).with(TransitionDetails.damageAfterReceipt("damage/" + UUID.randomUUID() + ".jpg")));
        engine.transition(TransitionCommand.of(d.jobId(), JobAction.OPS_RELEASE_CREATOR, ops, "seal broken inside")
                .with(TransitionDetails.reason(ReasonCode.DEFECTIVE_PRODUCT)));
        jobs.add(d.jobId());

        // ... and one that ops finds usable: production resumes with the time left.
        Seeded e = shipped();
        engine.transition(TransitionCommand.of(e.jobId(), JobAction.CONFIRM_RECEIVED, Actor.creator(e.creatorId()), null)
                .with(TransitionDetails.photo(JobFixtures.photoKey())));
        engine.transition(TransitionCommand.of(e.jobId(), JobAction.REPORT_DELIVERY_ISSUE, Actor.creator(e.creatorId()),
                null).with(TransitionDetails.damageAfterReceipt("damage/" + UUID.randomUUID() + ".mp4")));
        engine.transition(TransitionCommand.of(e.jobId(), JobAction.OPS_RESUME_PRODUCTION, ops, "usable"));
        jobs.add(e.jobId());

        EventSender real = new AiFeedbackSender(new AiFeedbackProperties(System.getenv("AI_CONTRACT_URL"),
                System.getenv().getOrDefault("AI_CONTRACT_KEY", "contract-check"), Duration.ofSeconds(2),
                Duration.ofSeconds(10)), mapper, json);

        List<Message> rows = jdbc.sql("""
                SELECT id, topic, message_key, event_type, envelope::text AS envelope FROM outbox
                WHERE message_key IN (:keys) ORDER BY seq
                """).param("keys", jobs.stream().map(UUID::toString).toList())
                .query((rs, n) -> new Message(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("message_key"), rs.getString("event_type"), rs.getString("envelope")))
                .list();
        assertThat(rows).hasSizeGreaterThanOrEqualTo(13);

        int sent = 0;
        for (Message m : rows) {
            Delivery first = real.send(m).get();
            assertThat(first).as("%s %s", m.eventType(), m.envelope()).isIn(Delivery.DELIVERED, Delivery.SKIPPED);
            if (first == Delivery.DELIVERED) {
                sent++;
                assertThat(real.send(m).get()).as("a resend is a duplicate, not a conflict").isEqualTo(Delivery.DELIVERED);
            }
        }
        assertThat(sent).as("the timer notice and every state change with a creator").isGreaterThanOrEqualTo(13);
    }

    /**
     * The business check: Backend A's own track-record code (its mock runs the real scoring on the events it got)
     * must read our events as intended. Five creators, five stories.
     */
    @Test
    void backendAsTrackRecordReadsOurEventsAsIntended() throws Exception {
        Instant before = clock.instant();
        clock.set(Instant.now());
        try {
            Actor ops = Actor.ops(UUID.randomUUID());
            Seeded onTime = fixtures.jobIn(JobStatus.IN_PRODUCTION);
            fixtures.setDeadline(onTime.jobId(), "production_due_at", clock.instant().plus(Duration.ofHours(10)));
            engine.transition(onTime.jobId(), JobAction.SUBMIT_DRAFT, Actor.creator(onTime.creatorId()), null);
            engine.transition(onTime.jobId(), JobAction.PREVIEW_READY, Actor.system(), null);
            engine.transition(onTime.jobId(), JobAction.APPROVE, Actor.brandAdmin(UUID.randomUUID(), onTime.brandOrgId()), null);
            engine.transition(onTime.jobId(), JobAction.PAYOUT_QUEUED, Actor.system(), null);
            engine.transition(onTime.jobId(), JobAction.PAYOUT_COMPLETED, Actor.system(), null);

            Seeded ghost = fixtures.jobIn(JobStatus.IN_PRODUCTION);
            fixtures.setDeadline(ghost.jobId(), "production_due_at", clock.instant().minus(Duration.ofMinutes(1)));
            engine.transition(ghost.jobId(), JobAction.PRODUCTION_EXPIRED, Actor.system(), null);

            Seeded lateShip = fixtures.jobIn(JobStatus.CREATOR_ACCEPTED);
            fixtures.setDeadline(lateShip.jobId(), "ship_due_at", clock.instant().minus(Duration.ofMinutes(1)));
            engine.transition(lateShip.jobId(), JobAction.CREATOR_WITHDRAW, Actor.creator(lateShip.creatorId()), null);

            Seeded accused = fixtures.jobIn(JobStatus.DELIVERY_ISSUE);
            engine.transition(accused.jobId(), JobAction.RESOLVE_FALSE_CLAIM, ops, "courier shows delivered");
            clock.advance(Duration.ofSeconds(1));
            engine.transition(TransitionCommand.of(accused.jobId(), JobAction.OPS_CORRECT_REASON, ops,
                    "the parcel was really lost").with(TransitionDetails.reason(ReasonCode.COURIER_LOST)));

            Seeded accusedOnly = fixtures.jobIn(JobStatus.DELIVERY_ISSUE);
            engine.transition(accusedOnly.jobId(), JobAction.RESOLVE_FALSE_CLAIM, ops, "courier shows delivered");

            List<Seeded> all = List.of(onTime, ghost, lateShip, accused, accusedOnly);
            EventSender real = new AiFeedbackSender(new AiFeedbackProperties(System.getenv("AI_CONTRACT_URL"),
                    System.getenv().getOrDefault("AI_CONTRACT_KEY", "contract-check"), Duration.ofSeconds(2),
                    Duration.ofSeconds(10)), mapper, json);
            List<Message> rows = jdbc.sql("""
                    SELECT id, topic, message_key, event_type, envelope::text AS envelope FROM outbox
                    WHERE message_key IN (:keys) ORDER BY seq
                    """).param("keys", all.stream().map(s -> s.jobId().toString()).toList())
                    .query((rs, n) -> new Message(rs.getObject("id", UUID.class), rs.getString("topic"),
                            rs.getString("message_key"), rs.getString("event_type"), rs.getString("envelope")))
                    .list();
            for (Message m : rows) {
                assertThat(real.send(m).get()).as(m.eventType()).isIn(Delivery.DELIVERED, Delivery.SKIPPED);
            }

            assertThat(track(onTime)).as("delivered on time").contains("\"jobs\":1", "\"on_time\":1",
                    "\"creator_faults\":0");
            assertThat(track(ghost)).as("ghosted: her fault").contains("\"creator_faults\":1");
            assertThat(track(lateShip)).as("the brand was late: nothing against her").isEqualTo("null");
            assertThat(track(accusedOnly)).as("accused, not corrected").contains("\"creator_faults\":1");
            assertThat(track(accused)).as("accused, then cleared by the correction").isEqualTo("null");
        } finally {
            clock.set(before);
        }
    }

    /** Registers the creator with the mock (handle = our id) and returns its track_record as JSON text. */
    private String track(Seeded s) throws Exception {
        String base = System.getenv("AI_CONTRACT_URL");
        String key = System.getenv().getOrDefault("AI_CONTRACT_KEY", "contract-check");
        String handle = "c" + s.creatorId().toString().replace("-", "").substring(0, 20);
        var http = java.net.http.HttpClient.newHttpClient();
        var profile = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/ai/creators/profile"))
                .header("X-API-Key", key).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"handle\":\"" + handle + "\",\"creator_ref\":\"" + s.creatorId() + "\"}")).build();
        assertThat(http.send(profile, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        var get = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/ai/creators/" + handle))
                .header("X-API-Key", key).GET().build();
        var body = json.readTree(http.send(get, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
        return json.writeValueAsString(body.get("track_record")).replace(" ", "");
    }

    private Seeded shipped() {
        Seeded seeded = fixtures.jobIn(JobStatus.PRODUCT_SHIPPED);
        jdbc.sql("""
                INSERT INTO shipments (id, job_id, assignment_id, attempt, courier, tracking_number, shipped_at,
                                       shipped_by, version)
                SELECT ?, ?, a.id, 1, 'Delhivery', 'DL1', now() - interval '1 day', ?, 0 FROM assignments a
                WHERE a.job_id = ? AND a.status = 'ACTIVE'
                """).params(Ids.newId(), seeded.jobId(), UUID.randomUUID(), seeded.jobId()).update();
        return seeded;
    }
}
