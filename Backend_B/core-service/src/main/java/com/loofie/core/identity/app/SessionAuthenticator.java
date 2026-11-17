package com.loofie.core.identity.app;

import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.loofie.core.identity.domain.AccountStatus;
import com.loofie.core.identity.domain.IdentityExceptions.AccountSuspendedException;
import com.loofie.core.identity.domain.SessionKind;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.security.UnauthenticatedException;

/**
 * Turns the session cookie into the {@link Actor} every other feature already checks. One indexed query per request,
 * remembered on the request so a controller and the CSRF check never look it up twice.
 */
@Component
public class SessionAuthenticator {

    private static final String ATTRIBUTE = SessionAuthenticator.class.getName() + ".session";
    private static final Optional<SessionView> NONE = Optional.empty();

    private final SessionService sessions;
    private final IdentityProperties props;

    public SessionAuthenticator(SessionService sessions, IdentityProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    public String cookieName() {
        return props.cookieName();
    }

    public String token(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (props.cookieName().equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /** The live session on this request, of any kind (MFA steps included). */
    @SuppressWarnings("unchecked")
    public Optional<SessionView> current(HttpServletRequest request) {
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached != null) {
            return (Optional<SessionView>) cached;
        }
        String token = token(request);
        Optional<SessionView> s = token == null ? NONE : sessions.lookup(token);
        request.setAttribute(ATTRIBUTE, s);
        return s;
    }

    /** A fully logged-in, active account. */
    public SessionView requireFull(HttpServletRequest request) {
        SessionView s = current(request).orElseThrow(() -> new UnauthenticatedException("Log in to continue"));
        if (s.kind() != SessionKind.FULL) {
            throw new UnauthenticatedException("Finish logging in with your authenticator app first");
        }
        if (s.status() != AccountStatus.ACTIVE) {
            throw new AccountSuspendedException();
        }
        return s;
    }

    public Actor actor(HttpServletRequest request) {
        SessionView s = requireFull(request);
        return switch (s.accountType()) {
            case CREATOR -> Actor.creator(s.userId());
            case ADMIN -> new Actor(s.staffRole(), s.userId(), null);
            // Brand roles come from organisation membership, which arrives in F1 round B.
            case BRAND -> throw new ActorNotAllowedException("Set up your company on Loofie before using this");
        };
    }

    /** Forgets the cached session (after login or logout on the same request). */
    public void clear(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE);
    }

}
