package com.loofie.core.identity.infra;

import static com.loofie.core.identity.infra.UserRepository.instant;
import static com.loofie.core.identity.infra.UserRepository.ts;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.job.domain.ActorRole;

@Repository
public class StaffInviteRepository {

    public record Invite(UUID id, String email, ActorRole role, UUID invitedBy, Instant expiresAt, Instant acceptedAt) {
    }

    private final JdbcClient jdbc;

    public StaffInviteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String email, ActorRole role, byte[] tokenHash, UUID invitedBy, Instant now,
                       Instant expires) {
        jdbc.sql("""
                INSERT INTO staff_invites (id, email, staff_role, token_hash, invited_by, created_at, expires_at)
                VALUES (:id, :email, :role, :hash, :by, :now, :expires)""")
                .param("id", id).param("email", email).param("role", role.name()).param("hash", tokenHash)
                .param("by", invitedBy).param("now", ts(now)).param("expires", ts(expires)).update();
    }

    public Optional<Invite> findForUpdate(byte[] tokenHash) {
        return jdbc.sql("""
                SELECT id, email, staff_role, invited_by, expires_at, accepted_at FROM staff_invites
                WHERE token_hash = :hash FOR UPDATE""").param("hash", tokenHash)
                .query((rs, n) -> new Invite(rs.getObject("id", UUID.class), rs.getString("email"),
                        ActorRole.valueOf(rs.getString("staff_role")), rs.getObject("invited_by", UUID.class),
                        instant(rs, "expires_at"), instant(rs, "accepted_at")))
                .optional();
    }

    public void markAccepted(UUID id, UUID userId, Instant now) {
        jdbc.sql("UPDATE staff_invites SET accepted_at = :now, accepted_user_id = :user WHERE id = :id")
                .param("id", id).param("user", userId).param("now", ts(now)).update();
    }
}
