package com.loofie.core.identity.domain;

import java.time.Instant;
import java.util.UUID;

import com.loofie.core.job.domain.ActorRole;

/**
 * A session looked up by its cookie, joined with what the request check needs from the account and, for brand users,
 * their company membership (read on every request, so a removal or demotion takes effect at once).
 *
 * @param orgId     the brand user's company, or null
 * @param brandRole BRAND_ADMIN or BRAND_MEMBER, or null
 */
public record SessionView(UUID sessionId, byte[] tokenHash, UUID userId, SessionKind kind, Instant createdAt,
                          Instant expiresAt, Instant absoluteExpiresAt, Instant lastSeenAt, Instant revokedAt,
                          AccountType accountType, AccountStatus status, ActorRole staffRole,
                          boolean onboardingCompleted, UUID orgId, ActorRole brandRole) {

    public boolean live(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt) && now.isBefore(absoluteExpiresAt);
    }
}
