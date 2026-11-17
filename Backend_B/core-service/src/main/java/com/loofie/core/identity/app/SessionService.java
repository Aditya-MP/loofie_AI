package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.loofie.core.common.Ids;
import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.SessionKind;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.identity.infra.SessionRepository;

/**
 * Logged-in devices. The cookie holds 256 random bits; the table holds only their SHA-256, so a database leak gives no
 * working session. Creators and brands: 30 days after last use, never more than 90 days. Staff: 12 hours, fixed.
 */
@Service
public class SessionService {

    /** Sliding expiry and last-seen are written at most this often per session, not on every request. */
    static final Duration TOUCH_EVERY = Duration.ofHours(1);
    /** Anything else in the cookie is ignored without a database read. */
    private static final int MIN_TOKEN = 20;
    private static final int MAX_TOKEN = 128;

    /** @param token the cookie value; shown to the client once, never stored */
    public record Issued(UUID sessionId, String token, SessionKind kind, Instant expiresAt, Duration cookieMaxAge,
                         String csrfToken) {
    }

    private final SessionRepository sessions;
    private final IdentityCrypto crypto;
    private final IdentityProperties props;
    private final Clock clock;

    public SessionService(SessionRepository sessions, IdentityCrypto crypto, IdentityProperties props, Clock clock) {
        this.sessions = sessions;
        this.crypto = crypto;
        this.props = props;
        this.clock = clock;
    }

    public Issued issue(Account account, SessionKind kind, String ip, String userAgent) {
        Instant now = clock.instant();
        Instant expires;
        Instant absolute;
        if (kind != SessionKind.FULL) {
            expires = now.plus(props.mfaSessionTtl());
            absolute = expires;
        } else if (account.isStaff()) {
            expires = now.plus(props.staffSession());
            absolute = expires;
        } else {
            expires = now.plus(props.userSessionIdle());
            absolute = now.plus(props.userSessionAbsolute());
        }
        String token = crypto.newToken();
        byte[] hash = IdentityCrypto.sha256(token);
        UUID id = Ids.newId();
        sessions.insert(id, hash, account.id(), kind, now, expires, absolute, ip, truncate(userAgent, 200));
        return new Issued(id, token, kind, expires, Duration.between(now, absolute), crypto.csrfToken(hash));
    }

    /** A live session for this cookie value, or empty. Renews a creator / brand session's idle expiry hourly. */
    public Optional<SessionView> lookup(String token) {
        if (token == null || token.length() < MIN_TOKEN || token.length() > MAX_TOKEN) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<SessionView> found = sessions.lookup(IdentityCrypto.sha256(token)).filter(s -> s.live(now));
        found.ifPresent(s -> {
            if (s.lastSeenAt().plus(TOUCH_EVERY).isBefore(now)) {
                boolean sliding = s.kind() == SessionKind.FULL && s.staffRole() == null;
                Instant next = sliding ? min(now.plus(props.userSessionIdle()), s.absoluteExpiresAt()) : s.expiresAt();
                sessions.touch(s.sessionId(), now, next);
            }
        });
        return found;
    }

    public String csrfToken(SessionView s) {
        return crypto.csrfToken(s.tokenHash());
    }

    public void revoke(UUID sessionId, String reason) {
        sessions.revoke(sessionId, reason, clock.instant());
    }

    public int revokeAll(UUID userId, String reason) {
        return sessions.revokeAll(userId, reason, clock.instant());
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
