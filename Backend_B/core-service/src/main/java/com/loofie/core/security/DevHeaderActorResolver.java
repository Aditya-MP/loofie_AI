package com.loofie.core.security;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;

/**
 * LOCAL DEVELOPMENT ONLY. Trusts plain headers to say who the caller is:
 * {@code X-Dev-Role} (BRAND_ADMIN, BRAND_MEMBER, CREATOR, OPS), {@code X-Dev-User-Id} and, for
 * brand roles, {@code X-Dev-Org-Id}. Active only under the "local" profile; every other profile
 * gets {@link DenyAllActorResolver} until real authentication replaces both.
 */
@Component
@Profile("local")
class DevHeaderActorResolver implements ActorResolver {

    static final String ROLE = "X-Dev-Role";
    static final String USER = "X-Dev-User-Id";
    static final String ORG = "X-Dev-Org-Id";

    private static final Logger log = LoggerFactory.getLogger(DevHeaderActorResolver.class);

    DevHeaderActorResolver() {
        log.warn("DEV AUTH ACTIVE: callers are identified by {} headers. Never run the 'local' profile in production.", ROLE);
    }

    @Override
    public Actor resolve(HttpServletRequest request) {
        String role = request.getHeader(ROLE);
        if (role == null || role.isBlank()) {
            throw new UnauthenticatedException("Missing " + ROLE + " header");
        }
        try {
            ActorRole r = ActorRole.valueOf(role.trim());
            if (r == ActorRole.SYSTEM) {
                throw new UnauthenticatedException("SYSTEM cannot be used from the API");
            }
            return new Actor(r, uuid(request.getHeader(USER)), uuid(request.getHeader(ORG)));
        } catch (IllegalArgumentException e) {
            throw new UnauthenticatedException("Invalid dev actor headers: " + e.getMessage());
        }
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value.trim());
    }
}
