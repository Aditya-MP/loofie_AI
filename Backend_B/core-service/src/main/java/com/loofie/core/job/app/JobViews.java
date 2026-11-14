package com.loofie.core.job.app;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonRawValue;

import com.loofie.core.job.domain.JobStatus;

/** Read models returned by {@link JobQueryService}. Plain records, safe to serialise as API responses. */
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

    public record EventView(UUID eventId, long jobVersion, String action, String fromState, String toState,
                            String actorType, UUID actorId, String reason, Instant occurredAt,
                            @JsonRawValue String payload) {
    }

    /** One shipment attempt (F6). A reship adds another. */
    public record ShipmentView(int attempt, String courier, String trackingNumber, Instant shippedAt,
                               Instant courierDeliveredAt, Instant receivedAt, boolean hasReceiptPhoto) {
    }

    public record JobDetail(JobSummary job, boolean freeRevisionUsed, String deliveryIssueType, List<Deadline> deadlines,
                            List<AssignmentView> assignments, List<ShipmentView> shipments, List<EventView> timeline) {
    }

    public record Page<T>(List<T> items, int page, int size, long total) {
    }
}
