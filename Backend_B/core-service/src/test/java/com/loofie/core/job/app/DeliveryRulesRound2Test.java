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
import com.loofie.core.job.domain.Courier;
import com.loofie.core.job.domain.DeliveryIssueType;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.ReasonCode;
import com.loofie.core.job.domain.Shipment;
import com.loofie.core.job.infra.JobRepository;
import com.loofie.core.job.infra.ShipmentRepository;
import com.loofie.core.job.read.JobViews.ShipmentView;
import com.loofie.core.order.app.OrderSubmissionService;
import com.loofie.core.order.app.OrderSubmissionService.Pick;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.MutableClock;
import com.loofie.core.support.TestSupportConfig;

/** F6 round 2: the five decisions of 2026-11-16 (docs/features/f6-product-delivery.md "Round 2"). */
@IntegrationTest
class DeliveryRulesRound2Test {

    @Autowired JobTransitionService engine;
    @Autowired OrderSubmissionService orders;
    @Autowired JobRepository jobs;
    @Autowired ShipmentRepository shipments;
    @Autowired CourierService couriers;
    @Autowired JobQueryService queries;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    final Actor ops = Actor.ops(UUID.randomUUID());

    @BeforeEach
    void monday() {
        clock.set(TestSupportConfig.START);
    }

    // ---- A. DEFECTIVE_PRODUCT ----

    @Test
    void defectiveProductIsTheBrandsFaultAndTheFaultComesFromTheCode() {
        Job job = received();
        report(job, DeliveryIssueType.DAMAGED);
        engine.transition(TransitionCommand.of(job.getId(), JobAction.OPS_RELEASE_CREATOR, ops, "seal broken inside")
                .with(TransitionDetails.reason(ReasonCode.DEFECTIVE_PRODUCT)));

        var last = jdbc.sql("""
                SELECT reason_code, payload->>'faultParty' AS fault FROM job_events
                WHERE job_id = ? ORDER BY occurred_at DESC, job_version DESC NULLS LAST LIMIT 1
                """).param(job.getId()).query().singleRow();
        assertThat(last.get("reason_code")).isEqualTo("DEFECTIVE_PRODUCT");
        assertThat(last.get("fault")).isEqualTo("BRAND");
    }

    // ---- B. Duplicate tracking numbers ----

    @Test
    void aNumberAnotherBrandAlreadyUsedIsBlockedEvenWrittenDifferently() {
        String number = JobFixtures.trackingNumber();
        UUID first = accepted(UUID.randomUUID(), UUID.randomUUID());
        ship(first, TransitionDetails.shipVia("DELHIVERY", number));

        UUID otherBrand = accepted(UUID.randomUUID(), UUID.randomUUID());
        String sameNumberTypedDifferently = " " + number.substring(0, 4).toLowerCase() + "-" + number.substring(4) + " ";
        assertThatThrownBy(() -> ship(otherBrand, TransitionDetails.shipVia("DELHIVERY", sameNumberTypedDifferently)))
                .isInstanceOf(TransitionGuardException.class)
                .hasMessage("This tracking number is already used. Check it or contact support.");
        assertThat(jobs.findById(otherBrand).orElseThrow().getStatus()).isEqualTo(JobStatus.CREATOR_ACCEPTED);
    }

    @Test
    void theSameBrandAndCreatorMayShareAParcelAndAnotherCreatorIsFlaggedForOps() {
        UUID brandOrg = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        String number = JobFixtures.trackingNumber();
        UUID a = accepted(brandOrg, creator);
        UUID b = accepted(brandOrg, creator);
        UUID c = accepted(brandOrg, UUID.randomUUID());

        ship(a, TransitionDetails.shipVia("DELHIVERY", number));
        ship(b, TransitionDetails.shipVia("DELHIVERY", number)); // one parcel, two jobs
        ship(c, TransitionDetails.shipVia("DELHIVERY", number)); // usually a copy-paste slip

        assertThat(reuse(b)).isNull();
        assertThat(reuse(c)).isEqualTo("SAME_BRAND_OTHER_CREATOR");
        assertThat(lastPayload(c, "trackingReused")).isEqualTo("SAME_BRAND_OTHER_CREATOR");
        assertThat(queries.detail(ops, c).shipments().getFirst().trackingReuse()).isEqualTo("SAME_BRAND_OTHER_CREATOR");
    }

    /** Two brands entering the same number at the same moment: the lock lets exactly one through. */
    @Test
    void twoBrandsShippingTheSameNumberAtOnceCannotBothSucceed() throws Exception {
        String number = JobFixtures.trackingNumber();
        List<UUID> jobsToShip = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            jobsToShip.add(accepted(UUID.randomUUID(), UUID.randomUUID())); // six different brands
        }
        var start = new java.util.concurrent.CountDownLatch(1);
        var ok = new java.util.concurrent.atomic.AtomicInteger();
        var blocked = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(jobsToShip.size())) {
            List<java.util.concurrent.Future<?>> runs = new java.util.ArrayList<>();
            for (UUID job : jobsToShip) {
                runs.add(pool.submit(() -> {
                    start.await();
                    try {
                        ship(job, TransitionDetails.shipVia("DELHIVERY", number));
                        ok.incrementAndGet();
                    } catch (TransitionGuardException e) {
                        blocked.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var run : runs) {
                run.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertThat(ok.get()).isEqualTo(1);
        assertThat(blocked.get()).isEqualTo(jobsToShip.size() - 1);
    }

    // ---- C. One reship, then ops ----

    @Test
    void theBrandReshipsOnceAndASecondProblemGoesToOps() {
        Job job = shipped();
        UUID brandOrg = job.getBrandOrgId();
        report(job, DeliveryIssueType.NOT_RECEIVED);
        engine.transition(TransitionCommand.of(job.getId(), JobAction.RESHIP, brand(brandOrg), null)
                .with(TransitionDetails.shipVia("BLUE_DART", JobFixtures.trackingNumber())));
        report(job, DeliveryIssueType.DAMAGED);

        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(job.getId(), JobAction.RESHIP, brand(brandOrg),
                null).with(TransitionDetails.shipVia("BLUE_DART", JobFixtures.trackingNumber()))))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("ops handles");

        engine.transition(TransitionCommand.of(job.getId(), JobAction.RESHIP, ops, "brand confirmed new parcel")
                .with(TransitionDetails.shipVia("BLUE_DART", JobFixtures.trackingNumber())));
        assertThat(shipments.countByJobId(job.getId())).isEqualTo(3);
        assertThat(status(job)).isEqualTo(JobStatus.PRODUCT_SHIPPED);
    }

    // ---- D. Courier table ----

    @Test
    void localDeliveryNeedsAHandoverPhotoInsteadOfATrackingNumber() {
        UUID job = accepted(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> ship(job, TransitionDetails.shipVia("LOCAL", null)))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("handover photo");
        assertThatThrownBy(() -> ship(job, TransitionDetails.localDelivery("https://evil.example/p.jpg")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("handover photo");

        ship(job, TransitionDetails.localDelivery("handovers/" + UUID.randomUUID() + ".jpg"));

        ShipmentView s = queries.detail(ops, job).shipments().getFirst();
        assertThat(s.courierCode()).isEqualTo("LOCAL");
        assertThat(s.trackingNumber()).isNull();
        assertThat(s.hasHandoverPhoto()).isTrue();
    }

    @Test
    void anOtherCourierNeedsItsNameANumberAndAnHttpsTrackingLink() {
        UUID job = accepted(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> ship(job, TransitionDetails.otherCourier(null, "SP1", "https://track.example/1")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("give its name");
        assertThatThrownBy(() -> ship(job, TransitionDetails.otherCourier("Speedy", "SP1", "javascript:alert(1)")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("tracking link");

        assertThatThrownBy(() -> ship(job, TransitionDetails.otherCourier("Speedy", null, "https://track.example/1")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("tracking number");
        ship(job, TransitionDetails.otherCourier("Speedy Couriers", JobFixtures.trackingNumber(), "https://track.example/SP1"));

        ShipmentView s = queries.detail(ops, job).shipments().getFirst();
        assertThat(s.courier()).isEqualTo("Speedy Couriers");
        assertThat(s.trackingLink()).isEqualTo("https://track.example/SP1");
    }

    @Test
    void aTrackedCourierNeedsTheNumberAndOpsGetsAOneClickLink() {
        couriers.upsert(ops, new Courier("TESTCOURIER", "Test Courier", Courier.Kind.TRACKED,
                "https://track.example/t?awb={tracking}", true, 5));
        UUID job = accepted(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> ship(job, TransitionDetails.shipVia("TESTCOURIER", " ")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("needs the tracking number");

        String number = "TC " + UUID.randomUUID().toString().substring(0, 8) + "&x";
        ship(job, TransitionDetails.shipVia("testcourier", number));

        assertThat(queries.detail(ops, job).shipments().getFirst().trackingLink())
                .as("the number is URL-encoded into the pattern")
                .isEqualTo("https://track.example/t?awb=" + java.net.URLEncoder.encode(number, java.nio.charset.StandardCharsets.UTF_8));
        couriers.upsert(ops, new Courier("TESTCOURIER", "Test Courier", Courier.Kind.TRACKED, null, false, 5));
    }

    @Test
    void anUnknownOrSwitchedOffCourierIsRefusedAndOldClientsAreMatchedByName() {
        couriers.upsert(ops, new Courier("RETIRED", "Retired Courier", Courier.Kind.TRACKED, null, false, 500));
        UUID job = accepted(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> ship(job, TransitionDetails.shipVia("NOPE", "X1")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("Unknown courier");
        assertThatThrownBy(() -> ship(job, TransitionDetails.shipVia("RETIRED", "X1")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("Unknown courier");
        assertThatThrownBy(() -> ship(job, TransitionDetails.shipment("Some Van", "X1")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("not in the list");
        assertThat(couriers.active()).extracting(CourierService.CourierOption::code).doesNotContain("RETIRED")
                .contains("DELHIVERY", "LOCAL", "OTHER");

        ship(job, TransitionDetails.shipment("blue dart", JobFixtures.trackingNumber()));
        assertThat(queries.detail(ops, job).shipments().getFirst().courierCode()).isEqualTo("BLUE_DART");
    }

    @Test
    void onlyOpsEditsCouriersAndTheFixedRowsStayFixed() {
        Courier ok = new Courier("NEWCO", "NewCo", Courier.Kind.TRACKED, "https://newco.example/{tracking}", true, 50);
        assertThatThrownBy(() -> couriers.upsert(Actor.brandAdmin(UUID.randomUUID(), UUID.randomUUID()), ok))
                .isInstanceOf(ActorNotAllowedException.class);
        for (Courier bad : List.of(
                new Courier("NEWCO", "NewCo", Courier.Kind.TRACKED, "http://newco.example/{tracking}", true, 50),
                new Courier("NEWCO", "NewCo", Courier.Kind.TRACKED, "https://newco.example/track", true, 50),
                new Courier("new co", "NewCo", Courier.Kind.TRACKED, null, true, 50),
                new Courier("NEWCO", "NewCo", Courier.Kind.LOCAL, null, true, 50),
                new Courier("LOCAL", "Local", Courier.Kind.TRACKED, null, true, 50),
                new Courier("OTHER", "Other", Courier.Kind.OTHER, null, false, 50))) {
            assertThatThrownBy(() -> couriers.upsert(ops, bad)).as(bad.toString())
                    .isInstanceOf(TransitionGuardException.class);
        }
        assertThat(couriers.upsert(ops, ok)).isTrue();
        assertThat(couriers.upsert(ops, ok)).isFalse();
    }

    // ---- E. Damage found after "Received" ----

    @Test
    void damageWithin24HoursPausesTheClockAndUsableResumesWithTheTimeLeft() {
        Job job = received();
        Instant due = job.getProductionDueAt();
        clock.advance(Duration.ofHours(10));

        engine.transition(TransitionCommand.of(job.getId(), JobAction.REPORT_DELIVERY_ISSUE, creatorOf(job), null)
                .with(TransitionDetails.damageAfterReceipt("damage/" + UUID.randomUUID() + ".mp4")));

        Job paused = reload(job);
        assertThat(paused.getStatus()).isEqualTo(JobStatus.DELIVERY_ISSUE);
        assertThat(paused.getProductionDueAt()).as("the clock stops").isNull();
        assertThat(paused.getMissingEscalateAt()).as("the product arrived: no missing-parcel rule").isNull();
        long left = Duration.between(clock.instant(), due).toSeconds();
        assertThat(paused.getProductionPausedSeconds()).isEqualTo(left);
        assertThat(lastPayload(job.getId(), "afterReceipt")).isEqualTo("true");

        clock.advance(Duration.ofDays(2)); // ops takes two days to look: none of it counts against the creator
        engine.transition(TransitionCommand.of(job.getId(), JobAction.OPS_RESUME_PRODUCTION, ops, "usable"));

        Job resumed = reload(job);
        assertThat(resumed.getStatus()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(resumed.getProductionDueAt()).isEqualTo(clock.instant().plusSeconds(left));
        assertThat(resumed.getProductionPausedSeconds()).isNull();
    }

    @Test
    void afterAReshipTheFull120HoursRestartOnTheNewReceipt() {
        Job job = received();
        clock.advance(Duration.ofHours(5));
        engine.transition(TransitionCommand.of(job.getId(), JobAction.REPORT_DELIVERY_ISSUE, creatorOf(job), null)
                .with(TransitionDetails.damageAfterReceipt("damage/" + UUID.randomUUID() + ".jpg")));
        engine.transition(TransitionCommand.of(job.getId(), JobAction.RESHIP, brand(job.getBrandOrgId()), null)
                .with(TransitionDetails.shipVia("DTDC", JobFixtures.trackingNumber())));
        assertThat(reload(job).getProductionPausedSeconds()).isNull();

        clock.advance(Duration.ofDays(3));
        engine.transition(TransitionCommand.of(job.getId(), JobAction.CONFIRM_RECEIVED, creatorOf(job), null)
                .with(TransitionDetails.photo(JobFixtures.photoKey())));

        assertThat(reload(job).getProductionDueAt()).isEqualTo(clock.instant().plus(JobDeadlines.PRODUCTION_WINDOW));
    }

    @Test
    void aFalseDamageClaimResumesWithTheTimeLeftNotAFresh120Hours() {
        Job job = received();
        Instant due = job.getProductionDueAt();
        clock.advance(Duration.ofHours(20));
        engine.transition(TransitionCommand.of(job.getId(), JobAction.REPORT_DELIVERY_ISSUE, creatorOf(job), null)
                .with(TransitionDetails.damageAfterReceipt("damage/" + UUID.randomUUID() + ".jpg")));
        long left = Duration.between(clock.instant(), due).toSeconds();

        clock.advance(Duration.ofHours(6));
        engine.transition(job.getId(), JobAction.RESOLVE_FALSE_CLAIM, ops, "photo shows an intact product");

        Job back = reload(job);
        assertThat(back.getStatus()).isEqualTo(JobStatus.IN_PRODUCTION);
        assertThat(back.getProductionDueAt()).isEqualTo(clock.instant().plusSeconds(left));
    }

    @Test
    void theCreatorReportsOnceWithin24HoursWithEvidenceAndOpsCanPauseAnyTime() {
        Job late = received();
        clock.advance(JobDeadlines.LATE_DAMAGE_WINDOW.plusMinutes(1));
        assertThatThrownBy(() -> damage(late, "damage/" + UUID.randomUUID() + ".jpg"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("within 24 hours");

        Job noEvidence = received();
        assertThatThrownBy(() -> damage(noEvidence, null))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("photo or short video");
        assertThatThrownBy(() -> damage(noEvidence, "https://evil.example/x.mp4"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("photo or short video");
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(noEvidence.getId(),
                JobAction.REPORT_DELIVERY_ISSUE, creatorOf(noEvidence), null)
                .with(TransitionDetails.issue(DeliveryIssueType.NOT_RECEIVED))))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("only damage");

        Job once = received();
        damage(once, "damage/" + UUID.randomUUID() + ".jpg");
        engine.transition(TransitionCommand.of(once.getId(), JobAction.OPS_RESUME_PRODUCTION, ops, "usable"));
        assertThatThrownBy(() -> damage(once, "damage/" + UUID.randomUUID() + ".jpg"))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("once per job");

        // Ops may still stop the clock for this job (e.g. the creator wrote in on day 3).
        engine.transition(TransitionCommand.of(late.getId(), JobAction.REPORT_DELIVERY_ISSUE, ops, "creator emailed photos")
                .with(TransitionDetails.issue(DeliveryIssueType.DAMAGED)));
        assertThat(reload(late).getStatus()).isEqualTo(JobStatus.DELIVERY_ISSUE);
        assertThat(reload(late).getProductionPausedSeconds()).isNotNull();

        Job notPaused = shipped();
        report(notPaused, DeliveryIssueType.NOT_RECEIVED);
        assertThatThrownBy(() -> engine.transition(TransitionCommand.of(notPaused.getId(),
                JobAction.OPS_RESUME_PRODUCTION, ops, "x")))
                .isInstanceOf(TransitionGuardException.class).hasMessageContaining("nothing to resume");
    }

    @Test
    void theEvidenceIsKeptOnTheShipmentForOps() {
        Job job = received();
        damage(job, "damage/" + UUID.randomUUID() + ".mov");
        ShipmentView s = queries.detail(ops, job.getId()).shipments().getFirst();
        assertThat(s.damageReportedAt()).isEqualTo(clock.instant());
        assertThat(s.hasDamageEvidence()).isTrue();
        assertThat(queries.detail(ops, job.getId()).productionPausedSeconds()).isPositive();
    }

    // ---- helpers ----

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

    /** Received with a photo: now in production with the 120-hour clock running. */
    private Job received() {
        Job job = shipped();
        engine.transition(TransitionCommand.of(job.getId(), JobAction.CONFIRM_RECEIVED, creatorOf(job), null)
                .with(TransitionDetails.photo(JobFixtures.photoKey())));
        return reload(job);
    }

    private void report(Job job, DeliveryIssueType type) {
        Actor who = status(job) == JobStatus.IN_PRODUCTION ? ops : creatorOf(job);
        engine.transition(TransitionCommand.of(job.getId(), JobAction.REPORT_DELIVERY_ISSUE, who,
                who == ops ? "ops report" : null).with(TransitionDetails.issue(type)));
    }

    private void damage(Job job, String evidence) {
        engine.transition(TransitionCommand.of(job.getId(), JobAction.REPORT_DELIVERY_ISSUE, creatorOf(job), null)
                .with(TransitionDetails.damageAfterReceipt(evidence)));
    }

    private Actor creatorOf(Job job) {
        UUID creator = jdbc.sql("SELECT creator_id FROM assignments WHERE job_id = ? AND status = 'ACTIVE'")
                .param(job.getId()).query(UUID.class).single();
        return Actor.creator(creator);
    }

    private Job reload(Job job) {
        return reload(job.getId());
    }

    private Job reload(UUID jobId) {
        return jobs.findById(jobId).orElseThrow();
    }

    private JobStatus status(Job job) {
        return reload(job).getStatus();
    }

    private String reuse(UUID jobId) {
        return (String) jdbc.sql("SELECT tracking_reuse FROM shipments WHERE job_id = ?").param(jobId).query().singleRow()
                .get("tracking_reuse");
    }

    private String lastPayload(UUID jobId, String field) {
        return jdbc.sql("""
                SELECT payload->>? FROM job_events WHERE job_id = ? AND event_type = 'JobStateChanged'
                ORDER BY job_version DESC NULLS LAST LIMIT 1
                """).params(field, jobId).query(String.class).single();
    }
}
