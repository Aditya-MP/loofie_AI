package com.loofie.core.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;

import com.loofie.core.common.Ids;
import com.loofie.core.housekeeping.RetentionJob;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.IdentityExceptions.CodeExpiredException;
import com.loofie.core.identity.infra.LoggingMessageSender;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.MutableClock;

/** Races, start-up refusals and clean-up for F1 round A. */
@IntegrationTest
class IdentitySafetyTest {

    @Autowired AuthService auth;
    @Autowired LoggingMessageSender messages;
    @Autowired JdbcClient jdbc;
    @Autowired RetentionJob retention;
    @Autowired MutableClock clock;

    @Test
    void twoVerifiesOfOneCodeAtOnceLogInExactlyOnce() throws Exception {
        String phone = newPhone();
        signUp(phone);
        OtpService.Sent sent = auth.sendLoginCode(Channel.PHONE, phone, ip());
        String code = code(phone);
        List<Object> outcomes = race(8, () -> auth.verifyLogin(sent.challengeId(), code, ip(), "test"));
        assertThat(outcomes.stream().filter(o -> o instanceof AuthService.Result).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof CodeExpiredException).count()).isEqualTo(7);
    }

    @Test
    void twoSignupsOfOnePhoneAtOnceMakeOneAccount() throws Exception {
        String phone = newPhone();
        List<UUID> challenges = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            OtpService.Sent s = auth.sendLoginCode(Channel.PHONE, phone, ip());
            auth.verifyLogin(s.challengeId(), code(phone), ip(), "test");
            challenges.add(s.challengeId());
        }
        Set<UUID> accounts = ConcurrentHashMap.newKeySet();
        List<Object> outcomes = race(2, new Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Object call() {
                AuthService.Result r = auth.signup(challenges.get(n.getAndIncrement()), AccountType.CREATOR,
                        new AuthService.SignupConsents(true, true, false), ip(), "test");
                accounts.add(r.account().id());
                return r;
            }
        });
        assertThat(outcomes).allMatch(o -> o instanceof AuthService.Result);
        assertThat(accounts).as("both people end up in the same account").hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE phone = :p").param("p", phone).query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    void prodRefusesToStartWithoutKeysOrWithDevKeys() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new IdentityCrypto(props(null, null, false), prod))
                .hasMessageContaining("LOOFIE_OTP_PEPPER");
        assertThatThrownBy(() -> new IdentityCrypto(props(null, null, true), prod))
                .hasMessageContaining("never be on under the prod profile");
        assertThatThrownBy(() -> new IdentityCrypto(props("c2hvcnQ=", base64(32), false), prod))
                .hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> new IdentityCrypto(props(base64(32), base64(16), false), prod))
                .hasMessageContaining("at least 32 bytes");
        IdentityCrypto ok = new IdentityCrypto(props(base64(48), base64(32), false), prod);
        byte[] secret = ok.newTotpSecret();
        byte[] sealed = ok.encryptTotp(secret);
        assertThat(sealed).isNotEqualTo(secret);
        assertThat(ok.decryptTotp(sealed)).isEqualTo(secret);
        IdentityCrypto otherKey = new IdentityCrypto(props(base64(48), base64(32), false), prod);
        assertThatThrownBy(() -> otherKey.decryptTotp(sealed)).hasMessageContaining("cannot be decrypted");
    }

    @Test
    void retentionClearsSpentLoginDataButKeepsConsentsAndRecentAudit() {
        Instant now = clock.instant();
        UUID user = Ids.newId();
        jdbc.sql("INSERT INTO users (id, account_type, status, phone, created_at, updated_at) VALUES (:id, 'CREATOR',"
                + " 'ACTIVE', :p, :t, :t)").param("id", user).param("p", newPhone()).param("t", ts(now)).update();
        UUID oldOtp = Ids.newId();
        jdbc.sql("INSERT INTO otp_challenges (id, purpose, channel, destination, code_hmac, created_at, expires_at)"
                + " VALUES (:id, 'LOGIN', 'PHONE', '+919800000000', '\\x00', :c, :c)")
                .param("id", oldOtp).param("c", ts(now.minus(Duration.ofDays(2)))).update();
        UUID oldSession = Ids.newId();
        jdbc.sql("INSERT INTO sessions (id, token_hash, user_id, kind, created_at, expires_at, absolute_expires_at,"
                + " last_seen_at) VALUES (:id, :h, :u, 'FULL', :c, :e, :e, :c)").param("id", oldSession)
                .param("h", UUID.randomUUID().toString().getBytes()).param("u", user)
                .param("c", ts(now.minus(Duration.ofDays(40)))).param("e", ts(now.minus(Duration.ofDays(10))))
                .update();
        UUID oldAudit = Ids.newId();
        UUID recentAudit = Ids.newId();
        for (UUID id : List.of(oldAudit, recentAudit)) {
            jdbc.sql("INSERT INTO audit_log (id, at, actor_role, action) VALUES (:id, :at, 'OPS', 'ACCOUNT_VIEWED')")
                    .param("id", id).param("at", ts(id == oldAudit ? now.minus(Duration.ofDays(366))
                            : now.minus(Duration.ofDays(200)))).update();
        }
        jdbc.sql("INSERT INTO consents (id, user_id, purpose, notice_version, granted_at) VALUES (:id, :u, 'SERVICE',"
                + " 'v0', :t)").param("id", Ids.newId()).param("u", user).param("t", ts(now.minus(Duration.ofDays(900))))
                .update();

        assertThat(retention.runOnce().identity()).isGreaterThanOrEqualTo(3);
        assertThat(exists("otp_challenges", oldOtp)).isFalse();
        assertThat(exists("sessions", oldSession)).isFalse();
        assertThat(exists("audit_log", oldAudit)).isFalse();
        assertThat(exists("audit_log", recentAudit)).as("CERT-In: at least 180 days").isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM consents WHERE user_id = :u").param("u", user).query(Long.class)
                .single()).as("consent history is never deleted by retention").isEqualTo(1);
    }

    private boolean exists(String table, UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    private void signUp(String phone) {
        OtpService.Sent s = auth.sendLoginCode(Channel.PHONE, phone, ip());
        auth.verifyLogin(s.challengeId(), code(phone), ip(), "test");
        auth.signup(s.challengeId(), AccountType.CREATOR, new AuthService.SignupConsents(true, true, false), ip(),
                "test");
    }

    private static List<Object> race(int n, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    return task.call();
                } catch (RuntimeException e) {
                    return e;
                }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    private String code(String to) {
        return messages.last(to).orElseThrow().text().substring(0, 6);
    }

    private static IdentityProperties props(String pepper, String totpKey, boolean dev) {
        return new IdentityProperties(null, pepper, totpKey, dev, null, null, null, null, null, null, 0, 0, 0, 0, 0,
                0, 0, null, null, null, null, null);
    }

    private static String base64(int bytes) {
        byte[] b = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    private static String newPhone() {
        return "+9197" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String ip() {
        return "10.250." + ThreadLocalRandom.current().nextInt(250) + "." + ThreadLocalRandom.current().nextInt(250);
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
