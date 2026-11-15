package com.loofie.core.job.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.IllegalTransitionException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.ReasonCode;
import com.loofie.core.job.domain.Shipment;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.job.infra.ShipmentRepository;
import com.loofie.core.order.app.OrderSubmissionService;
import com.loofie.core.order.app.OrderSubmissionService.Pick;
import com.loofie.core.job.domain.DeliverySource;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.MutableClock;

/** F6 product delivery: the DewDrop / Ananya story from docs/product-decisions.md, step by step. */
@IntegrationTest
class ProductDeliveryTest {

    /** Monday 16 Nov 2026, 10:00 India time. */
    static final Instant MONDAY_10_IST = Instant.parse("2026-11-16T04:30:00Z");

    @Autowired JobTransitionService engine;
    @Autowired OrderSubmissionService orders;
    @Autowired JobRepository jobs;
    @Autowired AssignmentRepository assignments;
    @Autowired ShipmentRepository shipments;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    final UUID brandOrg = UUID.randomUUID();
    final Actor brand = Actor.brandAdmin(UUID.randomUUID(), brandOrg);
    final Actor ops = Actor.ops(UUID.randomUUID());
    final UUID creatorId = UUID.randomUUID();
    final Actor creator = Actor.creator(creatorId);

    @BeforeEach
    void startOnMonday() {
        clock.set(MONDAY_10_IST);
    }

    // 1. The brand has 3 working days to ship

    @Test
    void acceptingStartsTheBrandsThreeWorkingDayShippingClock() {
        UUID jobId = acceptedJob();

        // Accepted Monday -> reminder at the end of Wednesday, due at the end of Thursday (India time).
        assertThat(job(jobId).getShipReminderAt()).isEqualTo(Instant.parse("2026-11-18T18:30:00Z"));
        assertThat(job(jobId).getShipDueAt()).isEqualTo(Instant.parse("2026-11-19T18:30:00Z"));
    }

    @Test
    void creatorMayWithdrawWithoutPenaltyOnlyOnceTheBrandIsLate() {
        UUID jobId = acceptedJob();

        assertThatThrownBy(() -> engine.transition(jobId, JobAction.CREATOR_WITHDRAW, creator, null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("late shipping");

        clock.set(job(jobId).getShipDueAt());
        TransitionResult r = engine.transition(jobId, JobAction.CREATOR_WITHDRAW, creator, null);

        assertThat(r.to()).isEqualTo(JobStatus.REPLACEMENT_NEEDED);
        assertThat(lastReasonCode(jobId)).isEqualTo("BRAND_LATE_SHIP"); // brand's fault: no reliability hit
        assertThat(assignments.findByJobIdAndStatus(jobId, Assignment.Status.ACTIVE)).isEmpty();
    }

    // 2. "Shipped" needs proof

    @Test
    void shippedNeedsCourierAndTrackingNumber() {
        UUID jobId = acceptedJob();

        assertThatThrownBy(() -> ship(jobId, "Delhivery", " "))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("tracking number");
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.MARK_SHIPPED, brand, null))
                .isInstanceOf(TransitionGuardException.class);

        ship(jobId, "Delhivery", "DL1234567890");

        Shipment s = shipments.findTopByJobIdOrderByAttemptDesc(jobId).orElseThrow();
        assertThat(s.getAttempt()).isEqualTo(1);
        assertThat(s.getCourier()).isEqualTo("Delhivery");
        assertThat(s.getTrackingNumber()).isEqualTo("DL1234567890");
        assertThat(job(jobId).getShipDueAt()).isNull();
        assertThat(job(jobId).getReceiptPromptAt()).isEqualTo(clock.instant().plus(JobDeadlines.RECEIPT_PROMPT_AFTER));
    }

    // 3. The creator's three buttons

    @Test
    void receivedNeedsAPhotoAndStartsTheShootingClock() {
        UUID jobId = shippedJob();

        assertThatThrownBy(() -> engine.transition(jobId, JobAction.CONFIRM_RECEIVED, creator, null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("photo");

        String photo = JobFixtures.photoKey();
        TransitionResult r = engine.transition(TransitionCommand.of(jobId, JobAction.CONFIRM_RECEIVED, creator, null)
                .with(TransitionDetails.photo(photo)));

        assertThat(r.to()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(job(jobId).getProductionDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.PRODUCTION_WINDOW));
        Shipment s = shipments.findTopByJobIdOrderByAttemptDesc(jobId).orElseThrow();
        assertThat(s.getReceivedAt()).isEqualTo(clock.instant());
        assertThat(s.getReceiptPhotoRef()).isEqualTo(photo);
    }

    @Test
    void notReceivedStopsTheClockAndGivesTheBrand48Hours() {
        UUID jobId = shippedJob();
        Instant shippedAt = clock.instant();
        clock.advance(Duration.ofDays(2));

        TransitionResult r = report(jobId, DeliveryIssueType.NOT_RECEIVED);

        assertThat(r.to()).isEqualTo(JobStatus.DELIVERY_ISSUE);
        Job job = job(jobId);
        assertThat(job.getDeliveryIssueType()).isEqualTo(DeliveryIssueType.NOT_RECEIVED);
        assertThat(job.getProductionDueAt()).isNull();
        assertThat(job.getIssueResponseDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.ISSUE_RESPONSE_WINDOW));
        assertThat(job.getMissingEscalateAt()).isEqualTo(shippedAt.plus(JobDeadlines.MISSING_AFTER_FIRST_SHIPPED));
        assertThat(lastPayloadField(jobId, "deliveryIssueType")).isEqualTo("NOT_RECEIVED");

        // The clock cannot start by itself while the issue is open.
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.AUTO_START_PRODUCTION, Actor.system(), null))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void damagedItemIsReshippedAndTheSevenDaysKeepCountingFromTheFirstShipment() {
        UUID jobId = shippedJob();
        Instant firstShipped = clock.instant();
        clock.advance(Duration.ofDays(2));
        report(jobId, DeliveryIssueType.DAMAGED);

        clock.advance(Duration.ofDays(1));
        TransitionResult r = engine.transition(TransitionCommand.of(jobId, JobAction.RESHIP, brand, null)
                .with(TransitionDetails.shipment("Blue Dart", "BD998877")));

        assertThat(r.to()).isEqualTo(JobStatus.PRODUCT_SHIPPED);
        assertThat(shipments.findByJobIdOrderByAttempt(jobId)).extracting(Shipment::getCourier)
                .containsExactly("Delhivery", "Blue Dart");
        assertThat(job(jobId).getDeliveryIssueType()).isNull();
        assertThat(job(jobId).getFirstShippedAt()).isEqualTo(firstShipped);

        // A second problem: the 7 days still run from the first "Shipped", so reshipping can't reset them.
        report(jobId, DeliveryIssueType.NOT_RECEIVED);
        assertThat(job(jobId).getMissingEscalateAt()).isEqualTo(firstShipped.plus(JobDeadlines.MISSING_AFTER_FIRST_SHIPPED));
    }

    @Test
    void parcelThatTurnsUpAfterAReportCanStillBeConfirmed() {
        UUID jobId = shippedJob();
        report(jobId, DeliveryIssueType.NOT_RECEIVED);

        TransitionResult r = engine.transition(TransitionCommand.of(jobId, JobAction.CONFIRM_RECEIVED, creator, null)
                .with(TransitionDetails.photo(JobFixtures.photoKey())));

        assertThat(r.to()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(job(jobId).getDeliveryIssueType()).isNull();
    }

    // 4. If the creator forgets to tap

    @Test
    void clockAutoStartsOnly24HoursAfterCourierDelivered() {
        UUID jobId = shippedJob();

        // No courier proof: never starts by itself, however long it has been.
        clock.advance(Duration.ofDays(6));
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.AUTO_START_PRODUCTION, Actor.system(), null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("courier");

        engine.transition(TransitionCommand.of(jobId, JobAction.COURIER_DELIVERED, ops, "Delhivery tracking shows Delivered")
                .with(TransitionDetails.courierDelivered(DeliverySource.TRACKING_PAGE, null)));
        Instant delivered = clock.instant();
        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.PRODUCT_SHIPPED);
        assertThat(job(jobId).getAutoStartAt()).isEqualTo(delivered.plus(JobDeadlines.AUTO_START_AFTER_DELIVERED));
        assertThat(job(jobId).getReceiptOpsCheckAt()).isNull();
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.COURIER_DELIVERED, ops, "again"))
                .isInstanceOf(TransitionGuardException.class);

        clock.advance(Duration.ofHours(23));
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.AUTO_START_PRODUCTION, Actor.system(), null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("24 hours");

        clock.advance(Duration.ofHours(1));
        TransitionResult r = engine.transition(jobId, JobAction.AUTO_START_PRODUCTION, Actor.system(), null);

        assertThat(r.to()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(eventPath(jobId)).endsWith("PRODUCT_SHIPPED>PRODUCT_SHIPPED", "PRODUCT_SHIPPED>PRODUCT_RECEIVED",
                "PRODUCT_RECEIVED>IN_PRODUCTION");
        assertThat(shipments.findTopByJobIdOrderByAttemptDesc(jobId).orElseThrow().getCourierDeliveredAt())
                .isEqualTo(delivered);
    }

    @Test
    void creatorCannotMarkCourierDelivered() {
        UUID jobId = shippedJob();
        assertThatThrownBy(() -> engine.transition(jobId, JobAction.COURIER_DELIVERED, creator, null))
                .isInstanceOf(ActorNotAllowedException.class);
    }

    // 5. If the parcel really goes missing

    @Test
    void creatorMayWithdrawSevenDaysAfterShippedWithACourierFaultCode() {
        UUID jobId = shippedJob();
        clock.advance(Duration.ofDays(3));
        report(jobId, DeliveryIssueType.NOT_RECEIVED);

        assertThatThrownBy(() -> engine.transition(jobId, JobAction.CREATOR_WITHDRAW, creator, null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("7 days");

        clock.set(job(jobId).getMissingEscalateAt());
        TransitionResult r = engine.transition(jobId, JobAction.CREATOR_WITHDRAW, creator, null);

        assertThat(r.to()).isEqualTo(JobStatus.REPLACEMENT_NEEDED);
        assertThat(lastReasonCode(jobId)).isEqualTo("COURIER_LOST");
        assertThat(lastPayloadField(jobId, "faultParty")).isEqualTo("COURIER");

        // The replacement creator starts with a clean shipment record.
        UUID next = UUID.randomUUID();
        engine.transition(TransitionCommand.of(jobId, JobAction.APPROVE_SWAP, brand, null).withCreator(next));
        assertThat(job(jobId).getFirstShippedAt()).isNull();
        assertThat(job(jobId).getDeliveryIssueType()).isNull();
    }

    @Test
    void opsCanReleaseTheCreatorEarlyButMustGiveAReasonCode() {
        UUID jobId = shippedJob();
        report(jobId, DeliveryIssueType.WRONG_ITEM);

        assertThatThrownBy(() -> engine.transition(jobId, JobAction.OPS_RELEASE_CREATOR, ops, "brand sent wrong SKU"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("reason code");

        engine.transition(TransitionCommand.of(jobId, JobAction.OPS_RELEASE_CREATOR, ops, "brand sent wrong SKU")
                .with(TransitionDetails.reason(ReasonCode.WRONG_ITEM_SENT)));

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.REPLACEMENT_NEEDED);
        assertThat(lastReasonCode(jobId)).isEqualTo("WRONG_ITEM_SENT");
    }

    // 6. If a creator lies

    @Test
    void opsResolvesAFalseClaimAndOnlyOpsCanAccuse() {
        UUID jobId = shippedJob();
        report(jobId, DeliveryIssueType.NOT_RECEIVED);

        // Nobody but ops can choose a reason code, and FALSE_CLAIM only through RESOLVE_FALSE_CLAIM.
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(jobId, JobAction.RESHIP, brand, null)
                .with(new TransitionDetails("Delhivery", "X1", null, null, ReasonCode.FALSE_CLAIM))))
                .isInstanceOf(ActorNotAllowedException.class);
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(jobId, JobAction.OPS_RELEASE_CREATOR, ops, "x")
                .with(TransitionDetails.reason(ReasonCode.FALSE_CLAIM))))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("FALSE_CLAIM");

        TransitionResult r = engine.transition(jobId, JobAction.RESOLVE_FALSE_CLAIM, ops,
                "Courier shows Delivered with delivery OTP");

        assertThat(r.to()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(r.eventIds()).hasSize(2);
        assertThat(reasonCodes(jobId)).contains("FALSE_CLAIM");
        assertThat(job(jobId).getProductionDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.PRODUCTION_WINDOW));
    }

    // ---- helpers ----

    private UUID acceptedJob() {
        UUID jobId = orders.submit(brandOrg, brand, List.of(new Pick(false, 15_000_00L, false))).jobIds().getFirst();
        engine.transition(jobId, JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        engine.transition(TransitionCommand.of(jobId, JobAction.INVITE, ops, "best fit").withCreator(creatorId));
        engine.transition(jobId, JobAction.ACCEPT, creator, null);
        return jobId;
    }

    // ---- F6 hardening (docs/features/f6-product-delivery.md plan items 1, 4, 6) ----

    /** Item 1: a receipt "photo" that is a link or script would be opened by ops. Only our storage keys pass. */
    @Test
    void aReceiptPhotoMustBeAnUploadedPhotoKeyNeverALinkOrScript() {
        UUID jobId = shippedJob();
        for (String bad : List.of("javascript:alert(1)", "https://evil.example/receipt.jpg", "receipts/../../etc.jpg",
                "receipts/not-a-uuid.jpg", "receipts/" + UUID.randomUUID() + ".exe", " receipts/" + UUID.randomUUID() + ".jpg")) {
            assertThatThrownBy(() -> engine.transition(TransitionCommand.of(jobId, JobAction.CONFIRM_RECEIVED, creator, null)
                    .with(TransitionDetails.photo(bad))))
                    .as(bad).isInstanceOf(TransitionGuardException.class).hasMessageContaining("photo key");
        }
        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.PRODUCT_SHIPPED);

        // The database refuses it too, whatever writes it.
        assertThatThrownBy(() -> jdbc.sql("UPDATE shipments SET receipt_photo_ref = 'javascript:alert(1)' WHERE job_id = ?")
                .param(jobId).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        engine.transition(TransitionCommand.of(jobId, JobAction.CONFIRM_RECEIVED, creator, null)
                .with(TransitionDetails.photo(JobFixtures.photoKey())));
        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.IN_PRODUCTION);
    }

    /** Item 4: ops records "Delivered" late; the creator's 24h window counts from the courier's delivery time. */
    @Test
    void courierDeliveredUsesTheCouriersDeliveryTimeAndSaysWhereItCameFrom() {
        UUID jobId = shippedJob();                    // shipped Tuesday 10:00
        clock.advance(Duration.ofDays(2));            // ops looks on Thursday
        Instant deliveredWednesday = clock.instant().minus(Duration.ofDays(1));

        assertThatThrownBy(() -> engine.transition(jobId, JobAction.COURIER_DELIVERED, ops, "tracking says delivered"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("TRACKING_PAGE or COURIER_CALL");
        assertThatThrownBy(() -> courierDelivered(jobId, DeliverySource.TRACKING_API, null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("integration only");
        assertThatThrownBy(() -> courierDelivered(jobId, DeliverySource.TRACKING_PAGE, clock.instant().plus(Duration.ofHours(1))))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> courierDelivered(jobId, DeliverySource.TRACKING_PAGE, clock.instant().minus(Duration.ofDays(5))))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("before the parcel was shipped");

        courierDelivered(jobId, DeliverySource.COURIER_CALL, deliveredWednesday);

        assertThat(job(jobId).getCourierDeliveredAt()).isEqualTo(deliveredWednesday);
        assertThat(job(jobId).getAutoStartAt()).isEqualTo(deliveredWednesday.plus(JobDeadlines.AUTO_START_AFTER_DELIVERED));
        Shipment s = shipments.findTopByJobIdOrderByAttemptDesc(jobId).orElseThrow();
        assertThat(s.getCourierDeliveredSource()).isEqualTo(DeliverySource.COURIER_CALL);
        assertThat(lastPayloadField(jobId, "deliverySource")).isEqualTo("COURIER_CALL");
        assertThat(lastPayloadField(jobId, "deliveredAt")).isEqualTo(deliveredWednesday.toString());
    }

    @Test
    void aTrackingIntegrationReportsDeliveredAsSystemWithoutSayingSo() {
        UUID jobId = shippedJob();
        engine.transition(jobId, JobAction.COURIER_DELIVERED, Actor.system(), null);
        assertThat(shipments.findTopByJobIdOrderByAttemptDesc(jobId).orElseThrow().getCourierDeliveredSource())
                .isEqualTo(DeliverySource.TRACKING_API);
    }

    /** Item 6: after a reship the first parcel turns up; the receipt is recorded on the right shipment and ops is told. */
    @Test
    void whenTheFirstParcelTurnsUpAfterAReshipItIsTheOneMarkedReceived() {
        UUID jobId = shippedJob();
        report(jobId, DeliveryIssueType.NOT_RECEIVED);
        engine.transition(TransitionCommand.of(jobId, JobAction.RESHIP, brand, null)
                .with(TransitionDetails.shipment("Blue Dart", "BD99887766")));

        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(jobId, JobAction.CONFIRM_RECEIVED, creator, null)
                .with(TransitionDetails.received(JobFixtures.photoKey(), 3))))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("no shipment attempt 3");

        engine.transition(TransitionCommand.of(jobId, JobAction.CONFIRM_RECEIVED, creator, null)
                .with(TransitionDetails.received(JobFixtures.photoKey(), 1)));

        List<Shipment> all = shipments.findByJobIdOrderByAttempt(jobId);
        assertThat(all.get(0).isReceived()).as("first parcel").isTrue();
        assertThat(all.get(1).isReceived()).as("reship still on its way").isFalse();
        String received = jdbc.sql("""
                SELECT payload::text FROM job_events WHERE job_id = ? AND action = 'CONFIRM_RECEIVED'
                """).param(jobId).query(String.class).single();
        assertThat(received).contains("\"shipmentAttempt\": 1").contains("\"olderShipmentReceived\": true");
        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.IN_PRODUCTION);
    }

    private void courierDelivered(UUID jobId, DeliverySource source, Instant at) {
        engine.transition(TransitionCommand.of(jobId, JobAction.COURIER_DELIVERED, ops, "checked")
                .with(TransitionDetails.courierDelivered(source, at)));
    }

    private UUID shippedJob() {
        UUID jobId = acceptedJob();
        clock.advance(Duration.ofDays(1));
        ship(jobId, "Delhivery", "DL1234567890");
        return jobId;
    }

    private TransitionResult ship(UUID jobId, String courier, String tracking) {
        return engine.transition(TransitionCommand.of(jobId, JobAction.MARK_SHIPPED, brand, null)
                .with(TransitionDetails.shipment(courier, tracking)));
    }

    private TransitionResult report(UUID jobId, DeliveryIssueType type) {
        return engine.transition(TransitionCommand.of(jobId, JobAction.REPORT_DELIVERY_ISSUE, creator, null)
                .with(TransitionDetails.issue(type)));
    }

    private Job job(UUID jobId) {
        return jobs.findById(jobId).orElseThrow();
    }

    private List<String> eventPath(UUID jobId) {
        return jdbc.sql("SELECT from_state || '>' || to_state FROM job_events WHERE job_id = ? ORDER BY job_version")
                .param(jobId).query(String.class).list();
    }

    private List<String> reasonCodes(UUID jobId) {
        return jdbc.sql("SELECT reason_code FROM job_events WHERE job_id = ? AND reason_code IS NOT NULL ORDER BY job_version")
                .param(jobId).query(String.class).list();
    }

    private String lastReasonCode(UUID jobId) {
        return jdbc.sql("SELECT reason_code FROM job_events WHERE job_id = ? ORDER BY job_version DESC NULLS LAST LIMIT 1")
                .param(jobId).query(String.class).single();
    }

    private String lastPayloadField(UUID jobId, String field) {
        return jdbc.sql("SELECT payload ->> ? FROM job_events WHERE job_id = ? ORDER BY job_version DESC NULLS LAST LIMIT 1")
                .params(field, jobId).query(String.class).single();
    }
}
