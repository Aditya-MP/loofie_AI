package com.loofie.core.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.loofie.core.identity.app.SessionAuthenticator;
import com.loofie.core.job.domain.Actor;

/**
 * Real authentication (F1 round A): the session cookie, nothing else. Every profile except {@code local}, and with no
 * profile at all. Replaces the deny-all placeholder that refused every request until F1.
 */
@Component
@Profile("!local")
class SessionActorResolver implements ActorResolver {

    private final SessionAuthenticator sessions;

    SessionActorResolver(SessionAuthenticator sessions) {
        this.sessions = sessions;
    }

    @Override
    public Actor resolve(HttpServletRequest request) {
        return sessions.actor(request);
    }
}
