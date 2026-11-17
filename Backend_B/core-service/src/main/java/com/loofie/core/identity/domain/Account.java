package com.loofie.core.identity.domain;

import java.time.Instant;
import java.util.UUID;

import com.loofie.core.job.domain.ActorRole;

/**
 * One person's account. {@code staffRole} is set only for ADMIN accounts (OPS, OPS_LEAD or FOUNDER); brand roles come
 * from organisation membership (round B).
 */
public record Account(UUID id, AccountType type, AccountStatus status, ActorRole staffRole, String phone, String email,
                      Instant phoneVerifiedAt, Instant emailVerifiedAt, boolean onboardingCompleted,
                      byte[] totpSecretEnc, Instant totpConfirmedAt, Long totpLastStep, String statusReason,
                      Instant createdAt) {

    public boolean isStaff() {
        return type == AccountType.ADMIN;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean totpReady() {
        return totpSecretEnc != null && totpConfirmedAt != null;
    }

    /** The phone or email this person logs in with. */
    public String loginContact() {
        return type == AccountType.CREATOR ? phone : email;
    }
}
