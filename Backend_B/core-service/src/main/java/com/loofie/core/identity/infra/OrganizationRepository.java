package com.loofie.core.identity.infra;

import static com.loofie.core.identity.infra.UserRepository.instant;
import static com.loofie.core.identity.infra.UserRepository.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.identity.domain.Organization;
import com.loofie.core.identity.domain.Organization.BillingAddress;
import com.loofie.core.identity.domain.Organization.Member;

/** organizations and org_members. */
@Repository
public class OrganizationRepository {

    private static final String COLUMNS = """
            id, legal_name, gstin, gstin_shared_reason, billing_line1, billing_line2, billing_city, billing_state,
            billing_pincode, pickup_state, created_by, created_at, updated_at""";


    private final JdbcClient jdbc;

    public OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the GSTIN is taken */
    public void insert(Organization o) {
        jdbc.sql("""
                INSERT INTO organizations (id, legal_name, gstin, gstin_set_by, gstin_set_at, billing_line1,
                    billing_line2, billing_city, billing_state, billing_pincode, pickup_state, created_by, created_at,
                    updated_at)
                VALUES (:id, :name, :gstin, :by, :gstinAt, :l1, :l2, :city, :state, :pin, :pickup, :by, :now, :now)""")
                .param("id", o.id()).param("name", o.legalName()).param("gstin", o.gstin())
                .param("gstinAt", o.gstin() == null ? null : ts(o.createdAt())).param("l1", o.billing().line1())
                .param("l2", o.billing().line2()).param("city", o.billing().city()).param("state", o.billing().state())
                .param("pin", o.billing().pincode()).param("pickup", o.pickupState()).param("by", o.createdBy())
                .param("now", ts(o.createdAt())).update();
    }

    public Optional<Organization> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM organizations WHERE id = :id").param("id", id)
                .query(OrganizationRepository::map).optional();
    }

    /** Locks the company for team changes, so "at least one admin" is read and kept under one lock. */
    public Optional<Organization> findForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM organizations WHERE id = :id FOR UPDATE").param("id", id)
                .query(OrganizationRepository::map).optional();
    }

    public void updateProfile(UUID id, String legalName, BillingAddress b, String pickupState, Instant now) {
        jdbc.sql("""
                UPDATE organizations SET legal_name = :name, billing_line1 = :l1, billing_line2 = :l2,
                       billing_city = :city, billing_state = :state, billing_pincode = :pin, pickup_state = :pickup,
                       updated_at = :now WHERE id = :id""")
                .param("id", id).param("name", legalName).param("l1", b.line1()).param("l2", b.line2())
                .param("city", b.city()).param("state", b.state()).param("pin", b.pincode())
                .param("pickup", pickupState).param("now", ts(now)).update();
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the GSTIN is taken and not shared */
    public void setGstin(UUID id, String gstin, String sharedReason, UUID by, Instant now) {
        jdbc.sql("""
                UPDATE organizations SET gstin = :gstin, gstin_shared_reason = :shared, gstin_set_by = :by,
                       gstin_set_at = :now, updated_at = :now WHERE id = :id""")
                .param("id", id).param("gstin", gstin).param("shared", sharedReason).param("by", by)
                .param("now", ts(now)).update();
    }

    public String pickupState(UUID orgId) {
        return jdbc.sql("SELECT pickup_state FROM organizations WHERE id = :id").param("id", orgId)
                .query(String.class).optional().orElse(null);
    }

    public boolean exists(UUID orgId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM organizations WHERE id = :id)")
                .param("id", orgId).query(Boolean.class).single();
    }

    /** @throws org.springframework.dao.DuplicateKeyException when the person already belongs to a company */
    public void addMember(UUID orgId, UUID userId, String role, UUID addedBy, Instant now) {
        jdbc.sql("""
                INSERT INTO org_members (org_id, user_id, role, added_by, added_at)
                VALUES (:org, :user, :role, :by, :now)""")
                .param("org", orgId).param("user", userId).param("role", role).param("by", addedBy)
                .param("now", ts(now)).update();
    }

    public Optional<Organization.Membership> membership(UUID userId) {
        return jdbc.sql("SELECT org_id, role FROM org_members WHERE user_id = :user").param("user", userId)
                .query((rs, n) -> new Organization.Membership(rs.getObject("org_id", UUID.class), rs.getString("role")))
                .optional();
    }

    public List<Member> members(UUID orgId) {
        return jdbc.sql("""
                SELECT m.user_id, m.role, u.email, m.added_at FROM org_members m JOIN users u ON u.id = m.user_id
                WHERE m.org_id = :org ORDER BY m.added_at, m.user_id""").param("org", orgId)
                .query((rs, n) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("role"),
                        rs.getString("email"), instant(rs, "added_at")))
                .list();
    }

    public long admins(UUID orgId) {
        return jdbc.sql("SELECT count(*) FROM org_members WHERE org_id = :org AND role = 'BRAND_ADMIN'")
                .param("org", orgId).query(Long.class).single();
    }

    public boolean setRole(UUID orgId, UUID userId, String role) {
        return jdbc.sql("UPDATE org_members SET role = :role WHERE org_id = :org AND user_id = :user")
                .param("org", orgId).param("user", userId).param("role", role).update() == 1;
    }

    public boolean removeMember(UUID orgId, UUID userId) {
        return jdbc.sql("DELETE FROM org_members WHERE org_id = :org AND user_id = :user")
                .param("org", orgId).param("user", userId).update() == 1;
    }

    static Organization map(ResultSet rs, int n) throws SQLException {
        return new Organization(rs.getObject("id", UUID.class), rs.getString("legal_name"), rs.getString("gstin"),
                rs.getString("gstin_shared_reason"), new BillingAddress(rs.getString("billing_line1"),
                rs.getString("billing_line2"), rs.getString("billing_city"), rs.getString("billing_state"),
                rs.getString("billing_pincode")), rs.getString("pickup_state"), rs.getObject("created_by", UUID.class),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }
}
