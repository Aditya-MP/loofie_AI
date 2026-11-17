package com.loofie.core.identity.infra;

import static com.loofie.core.identity.infra.UserRepository.instant;
import static com.loofie.core.identity.infra.UserRepository.ts;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.OtpChallenge;
import com.loofie.core.identity.domain.OtpPurpose;

@Repository
public class OtpRepository {

    private final JdbcClient jdbc;

    public OtpRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OtpChallenge c, String ip) {
        jdbc.sql("""
                INSERT INTO otp_challenges (id, purpose, channel, destination, user_id, code_hmac, attempts, created_at,
                                            expires_at, ip)
                VALUES (:id, :purpose, :channel, :dest, :user, :hmac, 0, :created, :expires, :ip)""")
                .param("id", c.id()).param("purpose", c.purpose().name()).param("channel", c.channel().name())
                .param("dest", c.destination()).param("user", c.userId()).param("hmac", c.codeHmac())
                .param("created", ts(c.createdAt())).param("expires", ts(c.expiresAt())).param("ip", ip).update();
    }

    /** Locks the challenge so two checks of one code never both succeed. */
    public Optional<OtpChallenge> findForUpdate(UUID id) {
        return jdbc.sql("""
                SELECT id, purpose, channel, destination, user_id, code_hmac, attempts, created_at, expires_at,
                       verified_at, consumed_at, failed_at
                FROM otp_challenges WHERE id = :id FOR UPDATE""").param("id", id)
                .query((rs, n) -> new OtpChallenge(rs.getObject("id", UUID.class),
                        OtpPurpose.valueOf(rs.getString("purpose")), Channel.valueOf(rs.getString("channel")),
                        rs.getString("destination"), rs.getObject("user_id", UUID.class), rs.getBytes("code_hmac"),
                        rs.getInt("attempts"), instant(rs, "created_at"), instant(rs, "expires_at"),
                        instant(rs, "verified_at"), instant(rs, "consumed_at"), instant(rs, "failed_at")))
                .optional();
    }

    public void wrongAttempt(UUID id) {
        jdbc.sql("UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = :id").param("id", id).update();
    }

    public void markVerified(UUID id, Instant now) {
        jdbc.sql("UPDATE otp_challenges SET verified_at = :now WHERE id = :id AND verified_at IS NULL")
                .param("id", id).param("now", ts(now)).update();
    }

    public void consume(UUID id, Instant now) {
        jdbc.sql("UPDATE otp_challenges SET consumed_at = :now WHERE id = :id").param("id", id).param("now", ts(now))
                .update();
    }

    public void markFailed(UUID id, Instant now) {
        jdbc.sql("UPDATE otp_challenges SET failed_at = :now WHERE id = :id").param("id", id).param("now", ts(now))
                .update();
    }
}
