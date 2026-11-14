package com.loofie.core.job.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is performing an action. Passed in explicitly so the domain never reads tokens or the
 * security context; the auth adapter (pending Shreya's design) builds this from the request.
 *
 * @param role   the caller's role
 * @param userId Backend A user id (creator id for creators); null only for SYSTEM
 * @param orgId  brand org id for brand roles; null otherwise
 */
public record Actor(ActorRole role, UUID userId, UUID orgId) {

    private static final Actor SYSTEM = new Actor(ActorRole.SYSTEM, null, null);

    public Actor {
        Objects.requireNonNull(role, "role");
        if (role != ActorRole.SYSTEM && userId == null) {
            throw new IllegalArgumentException(role + " actor needs a userId");
        }
        if (role.isBrand() && orgId == null) {
            throw new IllegalArgumentException(role + " actor needs an orgId");
        }
    }

    public static Actor system() {
        return SYSTEM;
    }

    public static Actor ops(UUID userId) {
        return new Actor(ActorRole.OPS, userId, null);
    }

    public static Actor creator(UUID creatorId) {
        return new Actor(ActorRole.CREATOR, creatorId, null);
    }

    public static Actor brandAdmin(UUID userId, UUID orgId) {
        return new Actor(ActorRole.BRAND_ADMIN, userId, orgId);
    }

    public static Actor brandMember(UUID userId, UUID orgId) {
        return new Actor(ActorRole.BRAND_MEMBER, userId, orgId);
    }
}
