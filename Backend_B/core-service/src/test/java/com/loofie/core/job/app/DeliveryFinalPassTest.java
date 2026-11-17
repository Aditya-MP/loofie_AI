package com.loofie.core.job.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.Courier;
import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.DeliverySource;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobExceptions.JobNotFoundException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.ReasonCode;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.job.read.JobViews.DeliveryShipment;
import com.loofie.core.job.read.JobViews.DeliveryView;
import com.loofie.core.notification.app.NotificationService;
import com.loofie.core.notification.app.NotificationService.Item;
import com.loofie.core.order.app.OrderSubmissionService;
import com.loofie.core.order.app.OrderSubmissionService.Pick;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.MutableClock;
import com.loofie.core.support.TestSupportConfig;

/** F6 final pass (docs/features/f6-product-delivery.md "Final pass"): notices, delivery screen, late damage after auto-start. */
@IntegrationTest
class DeliveryFinalPassTest {

    @Autowired JobTransitionService engine;
    @Autowired OrderSubmissionService orders;
    @Autowired JobRepository jobs;
    @Autowired CourierService couriers;
    @Autowired JobQueryService queries;
    @Autowired NotificationService notifications;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    final Actor ops = Actor.ops(UUID.randomUUID());

    @BeforeEach
    void monday() {
        clock.set(TestSupportConfig.START);
    }

    // ---- P1. Notices ----

    @Test
    void shippingTellsTheCreatorTheCourierNumberAndOneClickLink() {
        UUID job = accepted(UUID.randomUUID(), UUID.randomUUID());
        String number = JobFixtures.trackingNumber();
        ship(job, TransitionDetails.otherCourier("Speedy Local", number, "https://speedy.example/t/" + number));

        Map<String, Object> p = only(creatorOf(job), job, "PRODUCT_SHIPPED").payload();
        assertThat(p).containsEntry("courier", "Speedy Local").containsEntry("trackingNumber", number)
                .containsEntry("trackingLink", "https://speedy.example/t/" + number).containsEntry("shipmentAttempt", 1)
                .containsEntry("receiptPromptAt", reload(job).getReceiptPromptAt().toString());
        assertThat(inbox(brand(reload(job).getBrandOrgId()), job)).as("the brand did it; nothing to tell it").isEmpty();
    }

    @Test
    void aListedCourierWithAPatternGivesTheCreatorTheBuiltLink() {
        couriers.upsert(ops, new Courier("FINALPASS", "Final Pass Courier", Courier.Kind.TRACKED,
                "https://fp.example/track?awb={tracking}", true, 6));
        try {
            UUID job = accepted(UUID.randomUUID(), UUID.randomUUID());
            String number = JobFixtures.trackingNumber();
            ship(job, TransitionDetails.shipVia("FINALPASS", number));
            assertThat(only(creatorOf(job), job, "PRODUCT_SHIPPED").payload())
                    .containsEntry("trackingLink", "https://fp.example/track?awb=" + number);
        } finally {
            couriers.upsert(ops, new Courier("FINALPASS", "Final Pass Courier", Courier.Kind.TRACKED, null, false, 6));
        }
    }

    @Test
    void aReportedProblemTellsTheBrandAndOpsWhatAndByWhenToAnswer() {
        Job job = shipped();
        report(job, DeliveryIssueType.NOT_RECEIVED);

        Job now = reload(job);
        Item toBrand = only(brand(now.getBrandOrgId()), job.getId(), "DELIVERY_ISSUE_REPORTED");
        assertThat(toBrand.payload()).containsEntry("issueType", "NOT_RECEIVED").containsEntry("afterReceipt", false)
                .containsEntry("answerBy", now.getIssueResponseDueAt().toString());
        assertThat(only(ops, job.getId(), "DELIVERY_ISSUE_REPORTED").payload()).isEqualTo(toBrand.payload());
    }

    @Test
    void damageAfterReceiptAlsoTellsTheBrandAndOps() {
        Job job = received();
        damage(job, "damage/" + UUID.randomUUID() + ".jpg");
        assertThat(only(brand(job.getBrandOrgId()), job.getId(), "DELIVERY_ISSUE_REPORTED").payload())
                .containsEntry("issueType", "DAMAGED").containsEntry("afterReceipt", true);
        assertThat(only(ops, job.getId(), "DELIVERY_ISSUE_REPORTED")).isNotNull();
    }

    @Test
    void receivedTellsTheBrandProductionStartedWithTheDeadline() {
        Job job = received();
        assertThat(only(brand(job.getBrandOrgId()), job.getId(), "PRODUCTION_STARTED").payload())
                .containsEntry("productionDueAt", job.getProductionDueAt().toString())
                .containsEntry("autoStarted", false);
        assertThat(kinds(creatorOf(job), job.getId())).as("she tapped it herself").doesNotContain("PRODUCTION_STARTED");
    }

    @Test
    void courierProofAndTheAutomaticStartAreToldToTheCreatorAndBrand() {
        Job job = shipped();
        courierDelivered(job);
        assertThat(only(creatorOf(job), job.getId(), "COURIER_DELIVERED").payload())
                .containsEntry("deliveredAt", clock.instant().toString())
                .containsEntry("autoStartAt", clock.instant().plus(JobDeadlines.AUTO_START_AFTER_DELIVERED).toString());

        clock.advance(JobDeadlines.AUTO_START_AFTER_DELIVERED);
        engine.transition(job.getId(), JobAction.AUTO_START_PRODUCTION, Actor.system(), null);
        Job started = reload(job);
        for (Actor who : List.of(creatorOf(job), brand(job.getBrandOrgId()))) {
            assertThat(only(who, job.getId(), "PRODUCTION_STARTED").payload()).containsEntry("autoStarted", true)
                    .containsEntry("productionDueAt", started.getProductionDueAt().toString());
        }
    }

    @Test
    void anOlderParcelTurningUpAfterAReshipTellsTheBrandAndOps() {
        Job job = shipped();
        report(job, DeliveryIssueType.NOT_RECEIVED);
        engine.transition(TransitionCommand.of(job.getId(), JobAction.RESHIP, brand(job.getBrandOrgId()), null)
                .with(TransitionDetails.shipVia("BLUE_DART", JobFixtures.trackingNumber())));
        assertThat(only(creatorOf(job), job.getId(), "PRODUCT_RESHIPPED").payload()).containsEntry("shipmentAttempt", 2)
                .containsEntry("courier", "Blue Dart");

        engine.transition(TransitionCommand.of(job.getId(), JobAction.CONFIRM_RECEIVED, creatorOf(job), null)
                .with(TransitionDetails.received(JobFixtures.photoKey(), 1)));
        for (Actor who : List.of(ops, brand(job.getBrandOrgId()))) {
            assertThat(only(who, job.getId(), "OLDER_PARCEL_RECEIVED").payload()).containsEntry("shipmentAttempt", 1);
        }
    }

    @Test
    void aNumberReusedForAnotherCreatorOfTheSameBrandIsFlaggedToOps() {
        UUID brandOrg = UUID.randomUUID();
        String number = JobFixtures.trackingNumber();
        UUID first = accepted(brandOrg, UUID.randomUUID());
        UUID second = accepted(brandOrg, UUID.randomUUID());
        ship(first, TransitionDetails.shipVia("DELHIVERY", number));
        ship(second, TransitionDetails.shipVia("DELHIVERY", number));

        assertThat(kinds(ops, first)).doesNotContain("TRACKING_NUMBER_REUSED");
        assertThat(only(ops, second, "TRACKING_NUMBER_REUSED").payload())
                .containsEntry("reuse", "SAME_BRAND_OTHER_CREATOR");
    }

    @Test
    void resumingOrRejectingAClaimTellsTheCreatorAndBrandTheNewDeadline() {
        Job usable = received();
        damage(usable, "damage/" + UUID.randomUUID() + ".png");
        engine.transition(TransitionCommand.of(usable.getId(), JobAction.OPS_RESUME_PRODUCTION, ops, "usable"));
        String due = reload(usable).getProductionDueAt().toString();
        assertThat(only(creatorOf(usable), usable.getId(), "PRODUCTION_RESUMED").payload())
                .containsEntry("productionDueAt", due);
        assertThat(only(brand(usable.getBrandOrgId()), usable.getId(), "PRODUCTION_RESUMED")).isNotNull();

        Job falseClaim = shipped();
        report(falseClaim, DeliveryIssueType.NOT_RECEIVED);
        engine.transition(TransitionCommand.of(falseClaim.getId(), JobAction.RESOLVE_FALSE_CLAIM, ops, "signed POD"));
        assertThat(only(creatorOf(falseClaim), falseClaim.getId(), "DELIVERY_CLAIM_REJECTED").payload())
                .containsEntry("productionDueAt", reload(falseClaim).getProductionDueAt().toString());
    }

    @Test
    void aCreatorLeavingBecauseTheBrandShippedLateTellsTheBrandAndOps() {
        UUID job = accepted(UUID.randomUUID(), UUID.randomUUID());
        Actor creator = creatorOf(job);
        clock.set(reload(job).getShipDueAt().plusSeconds(1));
        engine.transition(job, JobAction.CREATOR_WITHDRAW, creator, null);

        assertThat(only(brand(reload(job).getBrandOrgId()), job, "CREATOR_WITHDREW").payload())
                .containsEntry("after", "BRAND_LATE_SHIP");
        assertThat(only(ops, job, "CREATOR_WITHDREW")).isNotNull();
    }

    @Test
    void aRefusedChangeTellsNobody() {
        String number = JobFixtures.trackingNumber();
        ship(accepted(UUID.randomUUID(), UUID.randomUUID()), TransitionDetails.shipVia("DELHIVERY", number));
        UUID blocked = accepted(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> ship(blocked, TransitionDetails.shipVia("DELHIVERY", number)))
                .isInstanceOf(TransitionGuardException.class);
        assertThat(inbox(creatorOf(blocked), blocked)).isEmpty();

        couriers.upsert(ops, new Courier("SWITCHEDOFF", "Switched Off", Courier.Kind.TRACKED, null, false, 501));
        Job job = shipped();
        report(job, DeliveryIssueType.NOT_RECEIVED);
        int before = inbox(creatorOf(job), job.getId()).size();
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(job.getId(), JobAction.RESHIP,
                brand(job.getBrandOrgId()), null).with(TransitionDetails.shipVia("SWITCHEDOFF", "SO123456"))))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("Unknown courier");
        assertThat(reload(job).getStatus()).isEqualTo(JobStatus.DELIVERY_ISSUE);
        assertThat(inbox(creatorOf(job), job.getId())).hasSize(before);
    }

    @Test
    void theNoticesCarryNoAddressesNotesOrEvidenceKeys() {
        Job job = received();
        String evidence = "damage/" + UUID.randomUUID() + ".jpg";
        damage(job, evidence);
        String all = jdbc.sql("SELECT string_agg(payload::text, ' ') FROM notifications WHERE job_id = ?")
                .param(job.getId()).query(String.class).single();
        assertThat(all).doesNotContain(evidence).doesNotContain("receipts/").doesNotContain("reason");
    }

    // ---- P2. Delivery screen ----

    @Test
    void theBrandAndTheCurrentCreatorSeeTheParcelAndNobodyElseDoes() {
        Job job = shipped();
        report(job, DeliveryIssueType.NOT_RECEIVED);
        UUID org = job.getBrandOrgId();
        Actor creator = creatorOf(job);

        DeliveryView forBrand = queries.delivery(Actor.brandMember(UUID.randomUUID(), org), job.getId());
        DeliveryView forCreator = queries.delivery(creator, job.getId());
        assertThat(forBrand).isEqualTo(forCreator);
        assertThat(forBrand.status()).isEqualTo(JobStatus.DELIVERY_ISSUE);
        assertThat(forBrand.issueType()).isEqualTo("NOT_RECEIVED");
        assertThat(forBrand.answerBy()).isEqualTo(reload(job).getIssueResponseDueAt());
        DeliveryShipment parcel = forBrand.shipments().getFirst();
        assertThat(parcel.courierCode()).isEqualTo("DELHIVERY");
        assertThat(parcel.trackingNumber()).isNotBlank();
        assertThat(queries.delivery(ops, job.getId())).isEqualTo(forBrand);

        assertThatThrownBy(() -> queries.delivery(brand(UUID.randomUUID()), job.getId()))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> queries.delivery(Actor.creator(UUID.randomUUID()), job.getId()))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> queries.delivery(Actor.system(), job.getId()))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> queries.delivery(creator, UUID.randomUUID()))
                .isInstanceOf(JobNotFoundException.class);

        // Released: she no longer sees the job, and the brand sees no parcel of hers.
        engine.transition(TransitionCommand.of(job.getId(), JobAction.OPS_RELEASE_CREATOR, ops, "lost for good")
                .with(TransitionDetails.reason(ReasonCode.COURIER_LOST)));
        assertThatThrownBy(() -> queries.delivery(creator, job.getId())).isInstanceOf(JobNotFoundException.class);
        assertThat(queries.delivery(brand(org), job.getId()).shipments()).isEmpty();
    }

    @Test
    void theScreenShowsReceiptAndDamageEvidenceAndThePausedClock() {
        Job job = received();
        String evidence = "damage/" + UUID.randomUUID() + ".mp4";
        damage(job, evidence);

        DeliveryView v = queries.delivery(brand(job.getBrandOrgId()), job.getId());
        DeliveryShipment parcel = v.shipments().getFirst();
        assertThat(parcel.receiptPhotoRef()).startsWith("receipts/");
        assertThat(parcel.damageEvidenceRef()).isEqualTo(evidence);
        assertThat(parcel.damageReportedAt()).isEqualTo(clock.instant());
        assertThat(v.productionPausedSeconds()).isPositive();
        assertThat(v.issueType()).isEqualTo("DAMAGED");
    }

    // ---- P3. Late damage after the automatic start ----

    @Test
    void aCreatorWhoNeverTappedReceivedMayReportDamageWithin24HoursOfTheAutomaticStart() {
        Job inTime = autoStarted();
        clock.advance(JobDeadlines.LATE_DAMAGE_WINDOW.minusMinutes(1));
        damage(inTime, "damage/" + UUID.randomUUID() + ".jpg");
        Job paused = reload(inTime);
        assertThat(paused.getStatus()).isEqualTo(JobStatus.DELIVERY_ISSUE);
        assertThat(paused.getProductionPausedSeconds()).isPositive();
        assertThat(queries.delivery(ops, inTime.getId()).shipments().getFirst().damageReportedAt())
                .as("recorded on the parcel the courier delivered").isEqualTo(clock.instant());

        Job late = autoStarted();
        clock.advance(JobDeadlines.LATE_DAMAGE_WINDOW.plusMinutes(1));
        assertThatThrownBy(() -> damage(late, "damage/" + UUID.randomUUID() + ".jpg"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("within 24 hours");
    }

    @Test
    void theAutomaticStartTimeIsClearedByAReshipAndForANewCreator() {
        Job job = autoStarted();
        assertThat(reload(job).getAutoStartedAt()).isEqualTo(clock.instant());
        engine.transition(TransitionCommand.of(job.getId(), JobAction.REPORT_DELIVERY_ISSUE, ops, "wrong shade")
                .with(TransitionDetails.issue(DeliveryIssueType.WRONG_ITEM)));
        engine.transition(TransitionCommand.of(job.getId(), JobAction.RESHIP, brand(job.getBrandOrgId()), null)
                .with(TransitionDetails.shipVia("BLUE_DART", JobFixtures.trackingNumber())));
        assertThat(reload(job).getAutoStartedAt()).isNull();
    }

    // ---- helpers ----

    private Item only(Actor who, UUID jobId, String kind) {
        List<Item> found = inbox(who, jobId).stream().filter(i -> i.kind().equals(kind)).toList();
        assertThat(found).as(kind + " for " + who.role()).hasSize(1);
        return found.getFirst();
    }

    private List<String> kinds(Actor who, UUID jobId) {
        return inbox(who, jobId).stream().map(Item::kind).toList();
    }

    private List<Item> inbox(Actor who, UUID jobId) {
        return notifications.inbox(who, false, null, jobId, 100);
    }

    private Actor brand(UUID brandOrg) {
        return Actor.brandAdmin(UUID.randomUUID(), brandOrg);
    }

    private UUID accepted(UUID brandOrg, UUID creator) {
        UUID jobId = orders.submit(brandOrg, brand(brandOrg), List.of(new Pick(false, 15_000_00L, false))).jobIds()
                .getFirst();
        engine.transition(jobId, JobAction.PAYMENT_CONFIRMED, Actor.system(), null);
        engine.transition(TransitionCommand.of(jobId, JobAction.INVITE, ops, "fit").withCreator(creator));
        engine.transition(jobId, JobAction.ACCEPT, Actor.creator(creator), null);
        return jobId;
    }

    private void ship(UUID jobId, TransitionDetails details) {
        UUID brandOrg = jobs.findById(jobId).orElseThrow().getBrandOrgId();
        engine.transition(TransitionCommand.of(jobId, JobAction.MARK_SHIPPED, brand(brandOrg), null).with(details));
    }

    private Job shipped() {
        UUID jobId = accepted(UUID.randomUUID(), UUID.randomUUID());
        ship(jobId, TransitionDetails.shipVia("DELHIVERY", JobFixtures.trackingNumber()));
        return reload(jobId);
    }

    private Job received() {
        Job job = shipped();
        engine.transition(TransitionCommand.of(job.getId(), JobAction.CONFIRM_RECEIVED, creatorOf(job), null)
                .with(TransitionDetails.photo(JobFixtures.photoKey())));
        return reload(job);
    }

    private Job autoStarted() {
        clock.set(TestSupportConfig.START);
        Job job = shipped();
        courierDelivered(job);
        clock.advance(JobDeadlines.AUTO_START_AFTER_DELIVERED);
        engine.transition(job.getId(), JobAction.AUTO_START_PRODUCTION, Actor.system(), null);
        return reload(job);
    }

    private void courierDelivered(Job job) {
        engine.transition(TransitionCommand.of(job.getId(), JobAction.COURIER_DELIVERED, ops, "tracking shows Delivered")
                .with(TransitionDetails.courierDelivered(DeliverySource.TRACKING_PAGE, null)));
    }

    private void report(Job job, DeliveryIssueType type) {
        engine.transition(TransitionCommand.of(job.getId(), JobAction.REPORT_DELIVERY_ISSUE, creatorOf(job), null)
                .with(TransitionDetails.issue(type)));
    }

    private void damage(Job job, String evidence) {
        engine.transition(TransitionCommand.of(job.getId(), JobAction.REPORT_DELIVERY_ISSUE, creatorOf(job), null)
                .with(TransitionDetails.damageAfterReceipt(evidence)));
    }

    private Actor creatorOf(Job job) {
        return creatorOf(job.getId());
    }

    private Actor creatorOf(UUID jobId) {
        UUID creator = jdbc.sql("SELECT creator_id FROM assignments WHERE job_id = ? AND status = 'ACTIVE'")
                .param(jobId).query(UUID.class).single();
        return Actor.creator(creator);
    }

    private Job reload(Job job) {
        return reload(job.getId());
    }

    private Job reload(UUID jobId) {
        return jobs.findById(jobId).orElseThrow();
    }
}
