package com.loofie.core.job.app;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.Assignment;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.domain.Shipment;
import com.loofie.core.job.infra.AssignmentRepository;
import com.loofie.core.job.infra.CourierRepository;
import com.loofie.core.job.infra.ShipmentRepository;
import com.loofie.core.notification.app.NotificationService;

/**
 * F6: tells the people who must act next that a delivery fact changed, in the same transaction as the change (never
 * lost, never sent for a change that rolled back). Timer reminders are raised by {@code JobNoticeService}; this is
 * the state-change side.
 *
 * <p>Payloads are ids, codes, times, and the parcel's courier / tracking number / link (which the brand entered and
 * the creator needs). No addresses, no ops notes, no evidence keys.
 */
@Component
public class DeliveryNotices {

    private final NotificationService notifications;
    private final AssignmentRepository assignments;
    private final ShipmentRepository shipments;
    private final CourierRepository couriers;

    public DeliveryNotices(NotificationService notifications, AssignmentRepository assignments,
                           ShipmentRepository shipments, CourierRepository couriers) {
        this.notifications = notifications;
        this.assignments = assignments;
        this.shipments = shipments;
        this.couriers = couriers;
    }

    /**
     * @param action the action that was asked for (not its automatic follow-ups)
     * @param start  the job's state before it
     * @param facts  the delivery facts the action recorded (shipment attempt, trackingReused, ...)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void after(Job job, JobAction action, JobStatus start, Map<String, Object> facts, Instant now) {
        UUID jobId = job.getId();
        switch (action) {
            case MARK_SHIPPED, RESHIP -> {
                creator(job).ifPresent(c -> notifications.toCreator(c,
                        action == JobAction.MARK_SHIPPED ? "PRODUCT_SHIPPED" : "PRODUCT_RESHIPPED", jobId, parcel(job), now));
                if (facts.get("trackingReused") != null) {
                    // Usually a copy-paste slip by the brand; ops checks it before it costs anyone a deadline.
                    notifications.toOps("TRACKING_NUMBER_REUSED", jobId,
                            Map.of("reuse", facts.get("trackingReused"), "shipmentAttempt", facts.get("shipmentAttempt")),
                            now);
                }
            }
            case COURIER_DELIVERED -> creator(job).ifPresent(c -> notifications.toCreator(c, "COURIER_DELIVERED", jobId,
                    times("deliveredAt", job.getCourierDeliveredAt(), "autoStartAt", job.getAutoStartAt()), now));
            case CONFIRM_RECEIVED, AUTO_START_PRODUCTION -> {
                Map<String, Object> started = times("productionDueAt", job.getProductionDueAt(), null, null);
                started.put("autoStarted", action == JobAction.AUTO_START_PRODUCTION);
                notifications.toBrand(job.getBrandOrgId(), "PRODUCTION_STARTED", jobId, started, now);
                if (action == JobAction.AUTO_START_PRODUCTION) {
                    // The creator never tapped Received: tell her the clock is running (and her 24 h damage window).
                    creator(job).ifPresent(c -> notifications.toCreator(c, "PRODUCTION_STARTED", jobId, started, now));
                }
                if (Boolean.TRUE.equals(facts.get("olderShipmentReceived"))) {
                    // The first parcel turned up after a reship: the brand can stop or recall the second one.
                    Map<String, Object> older = Map.of("shipmentAttempt", facts.get("shipmentAttempt"));
                    notifications.toBrand(job.getBrandOrgId(), "OLDER_PARCEL_RECEIVED", jobId, older, now);
                    notifications.toOps("OLDER_PARCEL_RECEIVED", jobId, older, now);
                }
            }
            case REPORT_DELIVERY_ISSUE -> {
                Map<String, Object> issue = times("answerBy", job.getIssueResponseDueAt(), null, null);
                issue.put("issueType", job.getDeliveryIssueType().name());
                issue.put("afterReceipt", start == JobStatus.IN_PRODUCTION);
                notifications.toBrand(job.getBrandOrgId(), "DELIVERY_ISSUE_REPORTED", jobId, issue, now);
                notifications.toOps("DELIVERY_ISSUE_REPORTED", jobId, issue, now);
            }
            case OPS_RESUME_PRODUCTION, RESOLVE_FALSE_CLAIM -> {
                Map<String, Object> resumed = times("productionDueAt", job.getProductionDueAt(), null, null);
                String kind = action == JobAction.OPS_RESUME_PRODUCTION ? "PRODUCTION_RESUMED" : "DELIVERY_CLAIM_REJECTED";
                creator(job).ifPresent(c -> notifications.toCreator(c, kind, jobId, resumed, now));
                notifications.toBrand(job.getBrandOrgId(), kind, jobId, resumed, now);
            }
            case CREATOR_WITHDRAW -> {
                if (start == JobStatus.CREATOR_ACCEPTED || start == JobStatus.DELIVERY_ISSUE) {
                    Map<String, Object> left = Map.of("after", start == JobStatus.CREATOR_ACCEPTED ? "BRAND_LATE_SHIP"
                            : "DELIVERY_PROBLEM");
                    notifications.toBrand(job.getBrandOrgId(), "CREATOR_WITHDREW", jobId, left, now);
                    notifications.toOps("CREATOR_WITHDREW", jobId, left, now);
                }
            }
            default -> {
                // not a delivery fact
            }
        }
    }

    private Optional<UUID> creator(Job job) {
        return assignments.findByJobIdAndStatus(job.getId(), Assignment.Status.ACTIVE).map(Assignment::getCreatorId);
    }

    /** What the creator needs to follow the parcel: courier, number and one-click link (latest attempt). */
    private Map<String, Object> parcel(Job job) {
        Map<String, Object> p = new LinkedHashMap<>();
        shipments.findTopByJobIdOrderByAttemptDesc(job.getId()).ifPresent(s -> {
            p.put("shipmentAttempt", s.getAttempt());
            p.put("courier", s.getCourier());
            putIfSet(p, "trackingNumber", s.getTrackingNumber());
            putIfSet(p, "trackingLink", link(s));
        });
        putIfSet(p, "receiptPromptAt", job.getReceiptPromptAt());
        return p;
    }

    private String link(Shipment s) {
        if (s.getTrackingUrl() != null) {
            return s.getTrackingUrl();
        }
        if (s.getCourierCode() == null || s.getTrackingNumber() == null) {
            return null;
        }
        return couriers.find(s.getCourierCode()).map(c -> c.trackingLink(s.getTrackingNumber())).orElse(null);
    }

    private static Map<String, Object> times(String k1, Instant v1, String k2, Instant v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfSet(m, k1, v1);
        if (k2 != null) {
            putIfSet(m, k2, v2);
        }
        return m;
    }

    private static void putIfSet(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value instanceof Instant i ? i.toString() : value);
        }
    }
}
