package com.loofie.core.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A brand's company.
 *
 * @param gstinSharedReason set only when ops allowed this company to use a GSTIN another company already has
 * @param pickupState       state code where parcels leave from; its state holidays count on brand-side clocks
 */
public record Organization(UUID id, String legalName, String gstin, String gstinSharedReason, BillingAddress billing,
                           String pickupState, UUID createdBy, Instant createdAt, Instant updatedAt) {

    public record BillingAddress(String line1, String line2, String city, String state, String pincode) {
    }

    /** One person in a company. */
    public record Member(UUID userId, String role, String email, Instant addedAt) {
    }

    /** A brand user's company and role (one company per person in v1). */
    public record Membership(UUID orgId, String role) {
    }

    /** An invite not yet accepted. */
    public record PendingInvite(UUID id, String email, String role, UUID invitedBy, Instant expiresAt) {
    }
}
