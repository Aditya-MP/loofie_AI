package com.loofie.core.identity.infra;

import static com.loofie.core.identity.infra.UserRepository.instant;
import static com.loofie.core.identity.infra.UserRepository.ts;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.identity.domain.AccountStatus;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.SessionKind;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.job.domain.ActorRole;

@Repository
public class SessionRepository {

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, byte[] tokenHash, UUID userId, SessionKind kind, Instant now, Instant expires,
                       Instant absoluteExpires, String ip, String userAgent) {
        jdbc.sql("""
                INSERT INTO sessions (id, token_hash, user_id, kind, created_at, expires_at, absolute_expires_at,
                                      last_seen_at, ip, user_agent)
                VALUES (:id, :hash, :user, :kind, :now, :expires, :absolute, :now, :ip, :ua)""")
                .param("id", id).param("hash", tokenHash).param("user", userId).param("kind", kind.name())
                .param("now", ts(now)).param("expires", ts(expires)).param("absolute", ts(absoluteExpires))
                .param("ip", ip).param("ua", userAgent).update();
    }

    /**
     * The one query every authenticated request runs: unique index on token_hash, primary key on users, unique index
     * on org_members.user_id (round B: a brand user's company and role, fresh on every request).
     */
    public Optional<SessionView> lookup(byte[] tokenHash) {
        return jdbc.sql("""
                SELECT s.id, s.token_hash, s.user_id, s.kind, s.created_at, s.expires_at, s.absolute_expires_at,
                       s.last_seen_at, s.revoked_at, u.account_type, u.status, u.staff_role, u.onboarding_completed,
                       m.org_id, m.role AS brand_role
                FROM sessions s JOIN users u ON u.id = s.user_id
                LEFT JOIN org_members m ON m.user_id = u.id
                WHERE s.token_hash = :hash""").param("hash", tokenHash)
                .query((rs, n) -> {
                    String role = rs.getString("staff_role");
                    String brandRole = rs.getString("brand_role");
                    return new SessionView(rs.getObject("id", UUID.class), rs.getBytes("token_hash"),
                            rs.getObject("user_id", UUID.class), SessionKind.valueOf(rs.getString("kind")),
                            instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "absolute_expires_at"),
                            instant(rs, "last_seen_at"), instant(rs, "revoked_at"),
                            AccountType.valueOf(rs.getString("account_type")),
                            AccountStatus.valueOf(rs.getString("status")),
                            role == null ? null : ActorRole.valueOf(role), rs.getBoolean("onboarding_completed"),
                            rs.getObject("org_id", UUID.class), brandRole == null ? null : ActorRole.valueOf(brandRole));
                }).optional();
    }

    /** Sliding expiry, written at most once an hour per session (not per request). */
    public void touch(UUID id, Instant now, Instant newExpires) {
        jdbc.sql("UPDATE sessions SET last_seen_at = :now, expires_at = :expires WHERE id = :id AND revoked_at IS NULL")
                .param("id", id).param("now", ts(now)).param("expires", ts(newExpires)).update();
    }

    public void revoke(UUID id, String reason, Instant now) {
        jdbc.sql("""
                UPDATE sessions SET revoked_at = :now, revoke_reason = :reason
                WHERE id = :id AND revoked_at IS NULL""").param("id", id).param("reason", reason)
                .param("now", ts(now)).update();
    }

    /** @return sessions ended */
    public int revokeAll(UUID userId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE sessions SET revoked_at = :now, revoke_reason = :reason
                WHERE user_id = :user AND revoked_at IS NULL""").param("user", userId).param("reason", reason)
                .param("now", ts(now)).update();
    }
}
