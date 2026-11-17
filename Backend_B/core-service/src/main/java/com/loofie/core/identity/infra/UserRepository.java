package com.loofie.core.identity.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.AccountStatus;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.job.domain.ActorRole;

/** users, consents and recovery_codes. */
@Repository
public class UserRepository {

    private static final String COLUMNS = """
            id, account_type, status, staff_role, phone, email, phone_verified_at, email_verified_at,
            onboarding_completed, totp_secret_enc, totp_confirmed_at, totp_last_step, status_reason, created_at
            """;


    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id").param("id", id).query(UserRepository::map)
                .optional();
    }

    public Optional<Account> findForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id FOR UPDATE").param("id", id)
                .query(UserRepository::map).optional();
    }

    public Optional<Account> findByContact(Channel channel, String value) {
        String col = channel == Channel.PHONE ? "phone" : "email";
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE " + col + " = :v").param("v", value)
                .query(UserRepository::map).optional();
    }

    /** @return false if the phone or email was taken first (a concurrent signup) */
    public boolean insertIfAbsent(UUID id, AccountType type, ActorRole staffRole, Channel channel, String contact,
                                  Instant verifiedAt, Instant adultConfirmedAt, Instant now) {
        String col = channel == Channel.PHONE ? "phone" : "email";
        return jdbc.sql("INSERT INTO users (id, account_type, status, staff_role, " + col + ", " + col + "_verified_at,"
                        + " adult_confirmed_at, created_at, updated_at) VALUES (:id, :type, 'ACTIVE', :role, :contact,"
                        + " :verified, :adult, :now, :now) ON CONFLICT DO NOTHING")
                .param("id", id).param("type", type.name()).param("role", staffRole == null ? null : staffRole.name())
                .param("contact", contact).param("verified", ts(verifiedAt)).param("adult", ts(adultConfirmedAt))
                .param("now", ts(now)).update() == 1;
    }

    public void markVerified(UUID id, Channel channel, Instant now) {
        String col = channel == Channel.PHONE ? "phone_verified_at" : "email_verified_at";
        jdbc.sql("UPDATE users SET " + col + " = :now, updated_at = :now WHERE id = :id AND " + col + " IS NULL")
                .param("id", id).param("now", ts(now)).update();
    }

    /** New, unconfirmed authenticator secret (enrolment or re-enrolment). */
    public void setTotpSecret(UUID id, byte[] encrypted, Instant now) {
        jdbc.sql("""
                UPDATE users SET totp_secret_enc = :s, totp_confirmed_at = NULL, totp_last_step = NULL, updated_at = :now
                WHERE id = :id""").param("id", id).param("s", encrypted).param("now", ts(now)).update();
    }

    public void clearTotp(UUID id, Instant now) {
        jdbc.sql("""
                UPDATE users SET totp_secret_enc = NULL, totp_confirmed_at = NULL, totp_last_step = NULL,
                       updated_at = :now WHERE id = :id""").param("id", id).param("now", ts(now)).update();
    }

    /**
     * Accepts this authenticator step only if it is newer than the last one used, atomically, so a code seen over
     * someone's shoulder cannot be replayed even by two requests at once.
     */
    public boolean acceptTotpStep(UUID id, long step, boolean confirm, Instant now) {
        return jdbc.sql("""
                UPDATE users SET totp_last_step = :step, updated_at = :now,
                       totp_confirmed_at = CASE WHEN :confirm THEN :now ELSE totp_confirmed_at END
                WHERE id = :id AND totp_secret_enc IS NOT NULL AND (totp_last_step IS NULL OR totp_last_step < :step)
                """).param("id", id).param("step", step).param("confirm", confirm).param("now", ts(now)).update() == 1;
    }

    public void setStatus(UUID id, AccountStatus status, String reason, UUID by, Instant now) {
        jdbc.sql("""
                UPDATE users SET status = :status, status_reason = :reason, status_changed_by = :by,
                       status_changed_at = :now, updated_at = :now WHERE id = :id""")
                .param("id", id).param("status", status.name()).param("reason", reason).param("by", by)
                .param("now", ts(now)).update();
    }

    /** @throws org.springframework.dao.DuplicateKeyException if another account has it */
    public void changeContact(UUID id, Channel channel, String value, Instant verifiedAt, Instant now) {
        String col = channel == Channel.PHONE ? "phone" : "email";
        jdbc.sql("UPDATE users SET " + col + " = :v, " + col + "_verified_at = :verified, updated_at = :now"
                        + " WHERE id = :id")
                .param("id", id).param("v", value).param("verified", ts(verifiedAt)).param("now", ts(now)).update();
    }

    public boolean anyFounder() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE staff_role = 'FOUNDER')").query(Boolean.class)
                .single();
    }

    public long activeFounders() {
        return jdbc.sql("SELECT count(*) FROM users WHERE staff_role = 'FOUNDER' AND status = 'ACTIVE'")
                .query(Long.class).single();
    }

    /** Serialises founder bootstrap across instances, within the caller's transaction. */
    public void lockFounderBootstrap() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('identity-founder-bootstrap'))").query().singleRow();
    }

    public void insertConsent(UUID id, UUID userId, String purpose, String noticeVersion, Instant at, String ip) {
        jdbc.sql("""
                INSERT INTO consents (id, user_id, purpose, notice_version, granted_at, ip)
                VALUES (:id, :user, :purpose, :version, :at, :ip)""")
                .param("id", id).param("user", userId).param("purpose", purpose).param("version", noticeVersion)
                .param("at", ts(at)).param("ip", ip).update();
    }

    /** @return how many open grants were withdrawn */
    public int withdrawConsent(UUID userId, String purpose, Instant at) {
        return jdbc.sql("""
                UPDATE consents SET withdrawn_at = :at
                WHERE user_id = :user AND purpose = :purpose AND withdrawn_at IS NULL""")
                .param("user", userId).param("purpose", purpose).param("at", ts(at)).update();
    }

    public List<com.loofie.core.identity.domain.Consent> consents(UUID userId) {
        return jdbc.sql("""
                SELECT id, purpose, notice_version, granted_at, withdrawn_at FROM consents
                WHERE user_id = :user ORDER BY granted_at DESC, id""").param("user", userId)
                .query((rs, n) -> new com.loofie.core.identity.domain.Consent(rs.getObject("id", UUID.class), rs.getString("purpose"),
                        rs.getString("notice_version"), instant(rs, "granted_at"), instant(rs, "withdrawn_at")))
                .list();
    }

    public void replaceRecoveryCodes(UUID userId, List<UUID> ids, List<byte[]> hmacs, Instant now) {
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = :user").param("user", userId).update();
        for (int i = 0; i < hmacs.size(); i++) {
            jdbc.sql("INSERT INTO recovery_codes (id, user_id, code_hmac, created_at) VALUES (:id, :user, :h, :now)")
                    .param("id", ids.get(i)).param("user", userId).param("h", hmacs.get(i)).param("now", ts(now))
                    .update();
        }
    }

    public void deleteRecoveryCodes(UUID userId) {
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = :user").param("user", userId).update();
    }

    /** Marks one unused recovery code used; false if there is no such unused code. */
    public boolean useRecoveryCode(UUID userId, byte[] hmac, Instant now) {
        return jdbc.sql("""
                UPDATE recovery_codes SET used_at = :now
                WHERE id = (SELECT id FROM recovery_codes WHERE user_id = :user AND code_hmac = :h AND used_at IS NULL
                            LIMIT 1 FOR UPDATE)""")
                .param("user", userId).param("h", hmac).param("now", ts(now)).update() == 1;
    }

    public int unusedRecoveryCodes(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM recovery_codes WHERE user_id = :user AND used_at IS NULL")
                .param("user", userId).query(Integer.class).single();
    }

    static Account map(ResultSet rs, int n) throws SQLException {
        String role = rs.getString("staff_role");
        long step = rs.getLong("totp_last_step");
        Long lastStep = rs.wasNull() ? null : step;
        return new Account(rs.getObject("id", UUID.class), AccountType.valueOf(rs.getString("account_type")),
                AccountStatus.valueOf(rs.getString("status")), role == null ? null : ActorRole.valueOf(role),
                rs.getString("phone"), rs.getString("email"), instant(rs, "phone_verified_at"),
                instant(rs, "email_verified_at"), rs.getBoolean("onboarding_completed"), rs.getBytes("totp_secret_enc"),
                instant(rs, "totp_confirmed_at"), lastStep, rs.getString("status_reason"), instant(rs, "created_at"));
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
