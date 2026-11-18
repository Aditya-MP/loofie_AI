package com.loofie.core.identity.infra;

import static com.loofie.core.identity.infra.UserRepository.instant;
import static com.loofie.core.identity.infra.UserRepository.ts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.identity.domain.Organization.PendingInvite;

/** One invites table (E1): staff invites have no org; brand teammate invites carry their company. */
@Repository
public class InviteRepository {

    /** @param role OPS / OPS_LEAD / FOUNDER for staff, BRAND_ADMIN / BRAND_MEMBER for a company */
    public record Invite(UUID id, String email, String role, UUID orgId, UUID invitedBy, Instant expiresAt,
                         Instant acceptedAt, Instant revokedAt) {

        public boolean usable(Instant now) {
            return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
        }
    }

    private final JdbcClient jdbc;

    public InviteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String email, String role, UUID orgId, byte[] tokenHash, UUID invitedBy, Instant now,
                       Instant expires) {
        jdbc.sql("""
                INSERT INTO invites (id, email, role, org_id, token_hash, invited_by, created_at, expires_at)
                VALUES (:id, :email, :role, :org, :hash, :by, :now, :expires)""")
                .param("id", id).param("email", email).param("role", role).param("org", orgId)
                .param("hash", tokenHash).param("by", invitedBy).param("now", ts(now)).param("expires", ts(expires))
                .update();
    }

    public Optional<Invite> findForUpdate(byte[] tokenHash) {
        return jdbc.sql("""
                SELECT id, email, role, org_id, invited_by, expires_at, accepted_at, revoked_at FROM invites
                WHERE token_hash = :hash FOR UPDATE""").param("hash", tokenHash)
                .query((rs, n) -> new Invite(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("role"), rs.getObject("org_id", UUID.class),
                        rs.getObject("invited_by", UUID.class), instant(rs, "expires_at"), instant(rs, "accepted_at"),
                        instant(rs, "revoked_at")))
                .optional();
    }

    public void markAccepted(UUID id, UUID userId, Instant now) {
        jdbc.sql("UPDATE invites SET accepted_at = :now, accepted_user_id = :user WHERE id = :id")
                .param("id", id).param("user", userId).param("now", ts(now)).update();
    }

    /** A new invite to the same person replaces the older open one. */
    public int revokeOpen(UUID orgId, String email, Instant now) {
        return jdbc.sql("""
                UPDATE invites SET revoked_at = :now
                WHERE org_id = :org AND email = :email AND accepted_at IS NULL AND revoked_at IS NULL""")
                .param("org", orgId).param("email", email).param("now", ts(now)).update();
    }

    public boolean revoke(UUID id, UUID orgId, Instant now) {
        return jdbc.sql("""
                UPDATE invites SET revoked_at = :now
                WHERE id = :id AND org_id = :org AND accepted_at IS NULL AND revoked_at IS NULL""")
                .param("id", id).param("org", orgId).param("now", ts(now)).update() == 1;
    }

    public List<PendingInvite> open(UUID orgId, Instant now) {
        return jdbc.sql("""
                SELECT id, email, role, invited_by, expires_at FROM invites
                WHERE org_id = :org AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > :now
                ORDER BY created_at""").param("org", orgId).param("now", ts(now))
                .query((rs, n) -> new PendingInvite(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("role"), rs.getObject("invited_by", UUID.class), instant(rs, "expires_at")))
                .list();
    }
}
