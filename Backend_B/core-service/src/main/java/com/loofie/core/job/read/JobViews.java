package com.loofie.core.job.read;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonRawValue;

import com.loofie.core.job.domain.JobStatus;

/**
 * Read models of the job feature (the query side): filled by {@code JobReadRepository}, returned by
 * {@code JobQueryService}. Plain records, safe to serialise as API responses. Kept in their own package
 * so the database layer can build them without depending on the application layer.
 */
public final class JobViews {

    private JobViews() {
    }

    /** Filters for the ops job list. Null or empty means "no filter". */
    public record JobFilter(Set<JobStatus> statuses, UUID brandOrgId, UUID orderId, boolean overdueOnly) {
    }

    /** The deadline that comes next for a job, e.g. {@code review_due_at}. */
    public record Deadline(String name, Instant at, boolean overdue) {
    }

    public record JobSummary(UUID jobId, UUID orderId, UUID brandOrgId, JobStatus status, boolean digitalOnly,
                             long creatorFeePaise, UUID activeCreatorId, Deadline nextDeadline, Instant updatedAt,
                             long version) {
    }

    public record AssignmentView(UUID assignmentId, UUID creatorId, String status, String closeReason,
                                 Instant openedAt, Instant closedAt) {
    }

    /** One timeline row. {@code jobVersion} is null for {@code TimerFired} rows (not a state change). */
    public record EventView(UUID eventId, Long jobVersion, String action, String fromState, String toState,
                            String actorType, UUID actorId, String reason, Instant occurredAt,
                            @JsonRawValue String payload) {
    }

    /** One shipment attempt (F6). A reship adds another. */
    /**
     * @param trackingLink   one click for ops: the courier's pattern with the number, or the "Other" link; null if none
     * @param trackingReuse  "SAME_BRAND_OTHER_CREATOR" when the number is on another creator's job of this brand: check it
     */
    public record ShipmentView(int attempt, String courier, String trackingNumber, Instant shippedAt,
                               Instant courierDeliveredAt, String courierDeliveredSource,
                               Instant courierDeliveredRecordedAt, Instant receivedAt, boolean hasReceiptPhoto,
                               String courierCode, String trackingLink, boolean hasHandoverPhoto, String trackingReuse,
                               Instant damageReportedAt, boolean hasDamageEvidence) {
    }

    /**
     * F6 for the brand and the current creator: one parcel of the current creator. Evidence keys are storage keys (F7
     * turns them into short-lived links); no ops-only facts (delivery source, duplicate-number flag).
     */
    public record DeliveryShipment(int attempt, String courier, String courierCode, String trackingNumber,
                                   String trackingLink, Instant shippedAt, Instant courierDeliveredAt,
                                   Instant receivedAt, String receiptPhotoRef, String handoverPhotoRef,
                                   Instant damageReportedAt, String damageEvidenceRef) {
    }

    /**
     * F6 delivery screen for the brand and the current creator.
     *
     * @param shipBy           the brand's ship-by time (while waiting for "Shipped")
     * @param issueType        what the creator reported, while the issue is open
     * @param answerBy         the brand's 48 h to answer an open issue
     * @param autoStartAt      production starts on its own here (24 h after courier proof) unless something is reported
     * @param productionDueAt  the running production deadline
     * @param productionPausedSeconds time left while a damage report has the clock stopped
     */
    public record DeliveryView(UUID jobId, JobStatus status, boolean digitalOnly, Instant shipBy, String issueType,
                               Instant answerBy, Instant autoStartAt, Instant productionDueAt,
                               Long productionPausedSeconds, List<DeliveryShipment> shipments) {
    }

    /** @param productionPausedSeconds production time left while a damage report after receipt has the clock stopped */
    public record JobDetail(JobSummary job, boolean freeRevisionUsed, String deliveryIssueType,
                            Long productionPausedSeconds, List<Deadline> deadlines, List<AssignmentView> assignments, List<ShipmentView> shipments, List<EventView> timeline) {
    }

    /**
     * One page of a list.
     *
     * @param total       matching rows, counted up to a cap; when {@code totalCapped} is true the real number is
     *                    larger ("10,000+"), because an exact count of a big list costs a full scan every call
     * @param nextCursor  pass as {@code after} to get the next page (keyset paging, same speed at any depth);
     *                    null when there are no more rows
     */
    public record Page<T>(List<T> items, int page, int size, long total, boolean totalCapped, String nextCursor) {
    }

    /** Position in the "newest change first" job list: the last row of the previous page. */
    public record Cursor(Instant updatedAt, UUID id) {

        /** Opaque to clients: base64url of "updatedAt|id". */
        public String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((updatedAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        /** @throws IllegalArgumentException for anything that is not a cursor this service issued */
        public static Cursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                int bar = raw.indexOf('|');
                return new Cursor(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        }
    }
}
