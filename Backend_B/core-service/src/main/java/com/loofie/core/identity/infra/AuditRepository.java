package com.loofie.core.identity.infra;

import static com.loofie.core.identity.infra.UserRepository.instant;
import static com.loofie.core.identity.infra.UserRepository.ts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.identity.domain.AuditEntry;

@Repository
public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEntry e) {
        jdbc.sql("""
                INSERT INTO audit_log (id, at, actor_id, actor_role, action, target_user_id, target_org_id, reason,
                                       details, request_id)
                VALUES (:id, :at, :actor, :role, :action, :target, :org, :reason, CAST(:details AS jsonb), :request)""")
                .param("id", e.id()).param("at", ts(e.at())).param("actor", e.actorId()).param("role", e.actorRole())
                .param("action", e.action()).param("target", e.targetUserId()).param("org", e.targetOrgId())
                .param("reason", e.reason()).param("details", e.detailsJson()).param("request", e.requestId())
                .update();
    }

    /** Newest first; filters are optional (null lists everything). Keyset on (at, id). */
    public List<AuditEntry> list(UUID targetUser, UUID targetOrg, Instant before, int limit) {
        return jdbc.sql("""
                SELECT id, at, actor_id, actor_role, action, target_user_id, target_org_id, reason,
                       details::text AS details, request_id
                FROM audit_log
                WHERE (CAST(:target AS uuid) IS NULL OR target_user_id = :target)
                  AND (CAST(:org AS uuid) IS NULL OR target_org_id = :org)
                  AND at < :before
                ORDER BY at DESC, id DESC LIMIT :limit""")
                .param("target", targetUser).param("org", targetOrg).param("before", ts(before)).param("limit", limit)
                .query((rs, n) -> new AuditEntry(rs.getObject("id", UUID.class), instant(rs, "at"),
                        rs.getObject("actor_id", UUID.class), rs.getString("actor_role"), rs.getString("action"),
                        rs.getObject("target_user_id", UUID.class), rs.getObject("target_org_id", UUID.class),
                        rs.getString("reason"), rs.getString("details"), rs.getObject("request_id", UUID.class)))
                .list();
    }
}
