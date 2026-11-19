package com.loofie.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.identity.domain.Totp;
import com.loofie.core.identity.infra.LoggingMessageSender;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F1 round A end to end over HTTP, against PostgreSQL: every flow on the agreed page, the security rules (enumeration,
 * CSRF, replay, suspension, limits) and the failure cases. Codes are read from the fake sender, as a person would read
 * their SMS. Each test uses its own IP and phone numbers so the shared rate counters never interfere.
 */
@IntegrationTest
@AutoConfigureMockMvc
class IdentityFlowTest {

    static final String COOKIE = "loofie_session"; // local profile: plain http, no __Host- prefix

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired LoggingMessageSender messages;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    private Instant before;
    private String ip;

    record Session(String token, String csrf) {
    }

    @BeforeEach
    void setUp() {
        before = clock.instant();
        ip = "10." + rnd(250) + "." + rnd(250) + "." + rnd(250);
        messages.failing(false);
    }

    @AfterEach
    void tearDown() {
        clock.set(before);
        messages.failing(false);
    }

    // ---- creators -------------------------------------------------------------------------------------------------

    @Test
    void creatorSignsUpWithPhoneThenUsesTheCookieOnARealEndpoint() throws Exception {
        String phone = newPhone();
        JsonNode sent = send("PHONE", phone.substring(3)); // typed without +91
        assertThat(sent.get("expiresInSeconds").asLong()).isBetween(1L, 300L);

        JsonNode verified = body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId",
                sent.get("challengeId").asString(), "code", code(phone)), null), 200);
        assertThat(verified.get("next").asString()).isEqualTo("SIGNUP_DETAILS_REQUIRED");
        assertThat(verified.get("signupAccountType").asString()).isEqualTo("CREATOR");

        MockHttpServletResponse r = call(post("/api/core/auth/signup"), Map.of("challengeId",
                sent.get("challengeId").asString(), "accountType", "CREATOR", "acceptTerms", true,
                "confirmAdult", true, "marketing", false), null);
        JsonNode done = body(r, 200);
        assertThat(done.get("next").asString()).isEqualTo("DONE");
        assertThat(done.get("user").get("role").asString()).isEqualTo("CREATOR");
        assertThat(done.get("user").get("phone").asString()).isEqualTo(phone);
        Cookie cookie = r.getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(r.getHeader("Set-Cookie")).contains("SameSite=Lax");

        Session s = new Session(cookie.getValue(), done.get("csrfToken").asString());
        // No dev headers: the creator is identified by the cookie alone.
        assertThat(call(get("/api/core/notifications"), null, s).getStatus()).isEqualTo(200);
        JsonNode me = body(call(get("/api/core/auth/me"), null, s), 200);
        assertThat(me.get("user").get("accountType").asString()).isEqualTo("CREATOR");
        assertThat(me.get("csrfToken").asString()).isEqualTo(s.csrf());

        // Consents recorded: service yes, marketing no; 18+ confirmed.
        assertThat(jdbc.sql("SELECT count(*) FROM consents c JOIN users u ON u.id = c.user_id WHERE u.phone = :p"
                + " AND purpose = 'SERVICE'").param("p", phone).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT adult_confirmed_at IS NOT NULL FROM users WHERE phone = :p").param("p", phone)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void existingCreatorIsLoggedInStraightAwayAndTheCodeWorksOnce() throws Exception {
        String phone = newPhone();
        signUpCreator(phone);
        JsonNode sent = send("PHONE", phone);
        String code = code(phone);
        Map<String, Object> verify = Map.of("challengeId", sent.get("challengeId").asString(), "code", code);
        JsonNode done = body(call(post("/api/core/auth/otp/verify"), verify, null), 200);
        assertThat(done.get("next").asString()).isEqualTo("DONE");
        assertThat(problem(call(post("/api/core/auth/otp/verify"), verify, null), 400)).isEqualTo("code-expired");
    }

    @Test
    void sendingLooksTheSameForKnownAndUnknownNumbers() throws Exception {
        String known = newPhone();
        signUpCreator(known);
        JsonNode a = send("PHONE", known);
        JsonNode b = send("PHONE", newPhone());
        assertThat(a.propertyNames()).containsExactlyInAnyOrderElementsOf(b.propertyNames());
    }

    @Test
    void wrongCodesAreCountedAndTheFifthKillsTheCode() throws Exception {
        String phone = newPhone();
        JsonNode sent = send("PHONE", phone);
        String id = sent.get("challengeId").asString();
        String wrong = code(phone).equals("000000") ? "111111" : "000000";
        for (int left = 4; left >= 1; left--) {
            JsonNode p = body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", wrong), null),
                    400);
            assertThat(p.get("attemptsLeft").asInt()).isEqualTo(left);
        }
        assertThat(problem(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", wrong), null),
                400)).isEqualTo("invalid-code");
        assertThat(problem(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", code(phone)),
                null), 400)).as("even the right code is refused after 5 wrong ones").isEqualTo("code-expired");
    }

    @Test
    void codesExpireAfterFiveMinutes() throws Exception {
        String phone = newPhone();
        JsonNode sent = send("PHONE", phone);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(problem(call(post("/api/core/auth/otp/verify"), Map.of("challengeId",
                sent.get("challengeId").asString(), "code", code(phone)), null), 400)).isEqualTo("code-expired");
    }

    @Test
    void threeCodesPer15MinutesPerPhoneThenRetryAfter() throws Exception {
        String phone = newPhone();
        for (int i = 0; i < 3; i++) {
            send("PHONE", phone);
        }
        MockHttpServletResponse r = call(post("/api/core/auth/otp/send"), Map.of("channel", "PHONE", "destination",
                phone), null);
        assertThat(problem(r, 429)).isEqualTo("rate-limited");
        assertThat(Long.parseLong(r.getHeader("Retry-After"))).isBetween(1L, 900L);
        clock.advance(Duration.ofMinutes(15));
        send("PHONE", phone); // next window
    }

    @Test
    void aFailedSendDoesNotUseUpTheAllowance() throws Exception {
        String phone = newPhone();
        messages.failing(true);
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse r = call(post("/api/core/auth/otp/send"), Map.of("channel", "PHONE",
                    "destination", phone), null);
            assertThat(problem(r, 503)).isEqualTo("delivery-unavailable");
            assertThat(r.getHeader("Retry-After")).isEqualTo("60");
        }
        messages.failing(false);
        for (int i = 0; i < 3; i++) {
            send("PHONE", phone);
        }
    }

    @Test
    void theDailySmsCapPausesTextsAndTellsOpsOnce() throws Exception {
        Instant day = clock.instant().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate()
                .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        jdbc.sql("INSERT INTO rate_counters (key, window_start, count) VALUES ('sms-day', :d, 5000) "
                + "ON CONFLICT (key, window_start) DO UPDATE SET count = 5000")
                .param("d", java.time.OffsetDateTime.ofInstant(day, java.time.ZoneOffset.UTC)).update();
        try {
            assertThat(problem(call(post("/api/core/auth/otp/send"), Map.of("channel", "PHONE", "destination",
                    newPhone()), null), 503)).isEqualTo("delivery-unavailable");
            call(post("/api/core/auth/otp/send"), Map.of("channel", "PHONE", "destination", newPhone()), null);
            assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE kind = 'SMS_DAILY_CAP_REACHED'")
                    .query(Long.class).single()).isEqualTo(1);
            send("EMAIL", newEmail()); // email is not capped
        } finally {
            jdbc.sql("DELETE FROM rate_counters WHERE key = 'sms-day'").update();
        }
    }

    @Test
    void signupRulesTypeMustMatchChannelAdminNeverAndConsentsRequired() throws Exception {
        String phone = newPhone();
        JsonNode sent = send("PHONE", phone);
        String id = sent.get("challengeId").asString();
        body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", code(phone)), null), 200);
        assertThat(problem(call(post("/api/core/auth/signup"), Map.of("challengeId", id, "accountType", "BRAND",
                "acceptTerms", true, "confirmAdult", true), null), 422)).isEqualTo("invalid-request");
        assertThat(problem(call(post("/api/core/auth/signup"), Map.of("challengeId", id, "accountType", "ADMIN",
                "acceptTerms", true, "confirmAdult", true), null), 422)).isEqualTo("invalid-request");
        assertThat(problem(call(post("/api/core/auth/signup"), Map.of("challengeId", id, "accountType", "CREATOR",
                "acceptTerms", true, "confirmAdult", false), null), 422)).isEqualTo("invalid-request");
        assertThat(problem(call(post("/api/core/auth/signup"), Map.of("challengeId", id, "accountType", "CREATOR",
                "acceptTerms", false, "confirmAdult", true), null), 422)).isEqualTo("invalid-request");
        // A signup without proving the code first is refused.
        String other = newPhone();
        JsonNode unverified = send("PHONE", other);
        assertThat(problem(call(post("/api/core/auth/signup"), Map.of("challengeId",
                unverified.get("challengeId").asString(), "accountType", "CREATOR", "acceptTerms", true,
                "confirmAdult", true), null), 400)).isEqualTo("code-expired");
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE phone IN (:a, :b)").param("a", phone)
                .param("b", other).query(Long.class).single()).isZero();
    }

    @Test
    void brandSignsUpWithEmailButCannotActUntilItHasACompany() throws Exception {
        String email = newEmail();
        JsonNode sent = send("EMAIL", email.toUpperCase());
        String id = sent.get("challengeId").asString();
        JsonNode v = body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", code(email)),
                null), 200);
        assertThat(v.get("signupAccountType").asString()).isEqualTo("BRAND");
        MockHttpServletResponse r = call(post("/api/core/auth/signup"), Map.of("challengeId", id, "accountType",
                "BRAND", "acceptTerms", true, "confirmAdult", true, "marketing", true), null);
        Session s = session(r);
        JsonNode me = body(call(get("/api/core/auth/me"), null, s), 200);
        assertThat(me.get("user").get("accountType").asString()).isEqualTo("BRAND");
        assertThat(me.get("user").get("role").isNull()).isTrue();
        assertThat(problem(call(get("/api/core/notifications"), null, s), 403)).isEqualTo("actor-not-allowed");
        // Marketing was ticked; withdrawing it is one call.
        JsonNode consents = body(call(put("/api/core/account/consents/marketing"), Map.of("granted", false), s), 200);
        assertThat(consents.valueStream().filter(c -> c.get("purpose").asString().equals("MARKETING"))
                .allMatch(c -> !c.get("withdrawnAt").isNull())).isTrue();
    }

    // ---- sessions, CSRF -------------------------------------------------------------------------------------------

    @Test
    void changesWithASessionNeedTheCsrfHeader() throws Exception {
        Session s = signUpCreator(newPhone());
        MockHttpServletResponse noToken = mvc.perform(withIp(post("/api/core/auth/logout"))
                .cookie(new Cookie(COOKIE, s.token()))).andReturn().getResponse();
        assertThat(problem(noToken, 403)).isEqualTo("csrf-token-invalid");
        MockHttpServletResponse wrongToken = mvc.perform(withIp(post("/api/core/auth/logout"))
                .cookie(new Cookie(COOKIE, s.token())).header("X-CSRF-Token", "forged")).andReturn().getResponse();
        assertThat(wrongToken.getStatus()).isEqualTo(403);
        assertThat(call(post("/api/core/auth/logout"), null, s).getStatus()).isEqualTo(204);
        assertThat(problem(call(get("/api/core/auth/me"), null, s), 401)).isEqualTo("unauthenticated");
    }

    @Test
    void logOutEverywhereEndsEveryDevice() throws Exception {
        String phone = newPhone();
        Session phoneA = signUpCreator(phone);
        Session phoneB = login(phone);
        assertThat(call(post("/api/core/auth/logout-all"), null, phoneA).getStatus()).isEqualTo(204);
        assertThat(call(get("/api/core/auth/me"), null, phoneB).getStatus()).isEqualTo(401);
    }

    @Test
    void creatorSessionsSlideForThirtyDaysButNeverPassNinety() throws Exception {
        Session s = signUpCreator(newPhone());
        for (int i = 0; i < 4; i++) { // used every 20 days: still logged in at day 80
            clock.advance(Duration.ofDays(20));
            assertThat(call(get("/api/core/auth/me"), null, s).getStatus()).isEqualTo(200);
        }
        clock.advance(Duration.ofDays(11)); // day 91
        assertThat(call(get("/api/core/auth/me"), null, s).getStatus()).isEqualTo(401);

        Session idle = signUpCreator(newPhone());
        clock.advance(Duration.ofDays(31));
        assertThat(call(get("/api/core/auth/me"), null, idle).getStatus()).isEqualTo(401);
    }

    @Test
    void aGarbageCookieIsJustNotLoggedIn() throws Exception {
        MockHttpServletResponse r = mvc.perform(withIp(get("/api/core/auth/me")).cookie(new Cookie(COOKIE, "x")))
                .andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(401);
        // A stale cookie does not block logging in again (no CSRF needed without a live session).
        String phone = newPhone();
        JsonNode sent = body(mvc.perform(withIp(post("/api/core/auth/otp/send"))
                .cookie(new Cookie(COOKIE, "stale-token-value-that-is-long-enough"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("channel", "PHONE", "destination", phone))))
                .andReturn().getResponse(), 202);
        assertThat(sent.get("challengeId")).isNotNull();
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        MockHttpServletResponse r = call(get("/api/core/auth/me"), null, null);
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void corsAllowsOnlyTheConfiguredAppWithCredentials() throws Exception {
        MockHttpServletResponse ok = mvc.perform(withIp(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.options("/api/core/auth/me"))
                .header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "GET"))
                .andReturn().getResponse();
        assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(ok.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        MockHttpServletResponse evil = mvc.perform(withIp(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.options("/api/core/auth/me"))
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
                .andReturn().getResponse();
        assertThat(evil.getStatus()).isEqualTo(403);
        assertThat(evil.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // ---- suspension ------------------------------------------------------------------------------------------------

    @Test
    void suspensionEndsSessionsAtOnceBlocksLoginTellsOpsAndIsAudited() throws Exception {
        String phone = newPhone();
        Session s = signUpCreator(phone);
        UUID creator = userId(phone);
        String opsId = UUID.randomUUID().toString();

        JsonNode suspended = body(ops(post("/api/core/ops/accounts/" + creator + "/suspend"), "OPS", opsId,
                Map.of("reason", "Fake follower screenshots")), 200);
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("phoneMasked").asString()).isEqualTo("+91******" + phone.substring(9));
        assertThat(call(get("/api/core/notifications"), null, s).getStatus()).isEqualTo(401);
        assertThat(messages.last(phone).orElseThrow().kind()).as("told by SMS").isEqualTo("ACCOUNT_SUSPENDED");
        assertThat(messages.last(phone).orElseThrow().text()).as("the fixed DLT template: no free text, no 'reply'")
                .isEqualTo("Your Loofie account has been suspended. To appeal, write to Loofie support from the app "
                        + "or the website.")
                .doesNotContain("Fake follower screenshots");

        JsonNode sent = send("PHONE", phone);
        assertThat(problem(call(post("/api/core/auth/otp/verify"), Map.of("challengeId",
                sent.get("challengeId").asString(), "code", code(phone)), null), 403)).isEqualTo("account-suspended");

        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE kind = 'ACCOUNT_SUSPENDED' AND payload->>'userId'"
                + " = :u").param("u", creator.toString()).query(Long.class).single()).isEqualTo(1);
        JsonNode audit = body(ops(get("/api/core/ops/audit-log?targetUserId=" + creator), "FOUNDER",
                UUID.randomUUID().toString(), null), 200);
        assertThat(audit.get(0).get("action").asString()).isEqualTo("ACCOUNT_SUSPENDED");
        assertThat(audit.get(0).get("actorId").asString()).isEqualTo(opsId);
        assertThat(audit.get(0).get("reason").asString()).isEqualTo("Fake follower screenshots");

        body(ops(post("/api/core/ops/accounts/" + creator + "/unsuspend"), "OPS", opsId,
                Map.of("reason", "Appeal accepted")), 200);
        login(phone);
    }

    @Test
    void suspendRulesReasonRequiredOpsOnlyAndStaffOnlyByFounder() throws Exception {
        UUID creator = userId(signUpCreatorPhone());
        assertThat(problem(ops(post("/api/core/ops/accounts/" + creator + "/suspend"), "OPS",
                UUID.randomUUID().toString(), Map.of("reason", "")), 422)).isEqualTo("invalid-request");
        assertThat(problem(ops(post("/api/core/ops/accounts/" + creator + "/suspend"), "CREATOR",
                UUID.randomUUID().toString(), Map.of("reason", "no")), 403)).isEqualTo("actor-not-allowed");
        UUID staff = createStaff("OPS").userId;
        assertThat(problem(ops(post("/api/core/ops/accounts/" + staff + "/suspend"), "OPS_LEAD",
                UUID.randomUUID().toString(), Map.of("reason", "Left the company")), 403))
                .isEqualTo("actor-not-allowed");
        body(ops(post("/api/core/ops/accounts/" + staff + "/suspend"), "FOUNDER", UUID.randomUUID().toString(),
                Map.of("reason", "Left the company")), 200);
        assertThat(problem(ops(get("/api/core/ops/audit-log"), "OPS", UUID.randomUUID().toString(), null), 403))
                .isEqualTo("actor-not-allowed");
    }

    // ---- staff ------------------------------------------------------------------------------------------------------

    @Test
    void staffJoinByInviteSetUpTheAuthenticatorAndUseOpsToolsWithTheCookie() throws Exception {
        Staff st = createStaff("OPS");
        assertThat(st.recoveryCodes).hasSize(10);
        // Cookie only, no dev headers: the ops endpoint sees an OPS actor.
        UUID creator = userId(signUpCreatorPhone());
        JsonNode view = body(call(get("/api/core/ops/accounts/" + creator), null, st.session), 200);
        assertThat(view.get("type").asString()).isEqualTo("CREATOR");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'ACCOUNT_VIEWED' AND actor_id = :a")
                .param("a", st.userId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT totp_secret_enc FROM users WHERE id = :id").param("id", st.userId)
                .query(byte[].class).single()).as("stored encrypted, not as the base32 secret")
                .isNotEqualTo(st.secret.getBytes());
    }

    @Test
    void staffLoginNeedsTheAuthenticatorAndACodeWorksOnlyOnce() throws Exception {
        Staff st = createStaff("OPS");
        clock.advance(Duration.ofSeconds(31));
        Session pending = emailLogin(st.email, "MFA_REQUIRED");
        // The pending session cannot act yet.
        assertThat(problem(call(get("/api/core/ops/accounts/" + st.userId), null, pending), 401))
                .isEqualTo("unauthenticated");
        String code = totp(st.secret);
        JsonNode done = body(call(post("/api/core/auth/mfa/verify"), Map.of("code", code), pending), 200);
        assertThat(done.get("user").get("role").asString()).isEqualTo("OPS");

        Session again = emailLogin(st.email, "MFA_REQUIRED");
        assertThat(problem(call(post("/api/core/auth/mfa/verify"), Map.of("code", code), again), 400))
                .as("the same code cannot be replayed").isEqualTo("invalid-code");
        clock.advance(Duration.ofSeconds(30));
        body(call(post("/api/core/auth/mfa/verify"), Map.of("code", totp(st.secret)), again), 200);
    }

    @Test
    void recoveryCodesWorkOnceEach() throws Exception {
        Staff st = createStaff("OPS_LEAD");
        Session p1 = emailLogin(st.email, "MFA_REQUIRED");
        body(call(post("/api/core/auth/mfa/verify"), Map.of("recoveryCode", st.recoveryCodes.get(0).toUpperCase()),
                p1), 200);
        Session p2 = emailLogin(st.email, "MFA_REQUIRED");
        assertThat(problem(call(post("/api/core/auth/mfa/verify"), Map.of("recoveryCode",
                st.recoveryCodes.get(0)), p2), 400)).isEqualTo("invalid-code");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'RECOVERY_CODE_USED' AND actor_id = :a")
                .param("a", st.userId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void authenticatorTriesAreLimited() throws Exception {
        Staff st = createStaff("OPS");
        Session p = emailLogin(st.email, "MFA_REQUIRED");
        for (int i = 0; i < 5; i++) {
            call(post("/api/core/auth/mfa/verify"), Map.of("code", "000000"), p);
        }
        assertThat(problem(call(post("/api/core/auth/mfa/verify"), Map.of("code", totp(st.secret)), p), 429))
                .isEqualTo("rate-limited");
    }

    @Test
    void lostPhoneResetBySeniorMeansSetupAgainAtNextLogin() throws Exception {
        Staff st = createStaff("OPS");
        String lead = UUID.randomUUID().toString();
        assertThat(ops(post("/api/core/ops/staff/" + st.userId + "/authenticator-reset"), "OPS_LEAD", lead,
                Map.of("reason", "Phone stolen, checked on video call")).getStatus()).isEqualTo(204);
        assertThat(call(get("/api/core/auth/me"), null, st.session).getStatus()).as("sessions ended").isEqualTo(401);
        emailLogin(st.email, "MFA_ENROL_REQUIRED");
        // OPS_LEAD cannot reset another OPS_LEAD; nobody resets their own.
        Staff otherLead = createStaff("OPS_LEAD");
        assertThat(problem(ops(post("/api/core/ops/staff/" + otherLead.userId + "/authenticator-reset"), "OPS_LEAD",
                lead, Map.of("reason", "Lost phone")), 403)).isEqualTo("actor-not-allowed");
        assertThat(problem(ops(post("/api/core/ops/staff/" + otherLead.userId + "/authenticator-reset"), "FOUNDER",
                otherLead.userId.toString(), Map.of("reason", "Lost phone")), 403)).isEqualTo("actor-not-allowed");
    }

    @Test
    void inviteRulesAndInvitesAreSingleUse() throws Exception {
        String email = newEmail();
        assertThat(problem(ops(post("/api/core/ops/staff/invites"), "OPS_LEAD", UUID.randomUUID().toString(),
                Map.of("email", email, "role", "OPS_LEAD")), 403)).isEqualTo("actor-not-allowed");
        assertThat(problem(ops(post("/api/core/ops/staff/invites"), "OPS", UUID.randomUUID().toString(),
                Map.of("email", email, "role", "OPS")), 403)).isEqualTo("actor-not-allowed");
        JsonNode invite = body(ops(post("/api/core/ops/staff/invites"), "OPS_LEAD", UUID.randomUUID().toString(),
                Map.of("email", email, "role", "OPS")), 201);
        assertThat(invite.get("emailSent").asBoolean()).isTrue();
        String token = invite.get("link").asString().substring(invite.get("link").asString().indexOf('#') + 1);

        // Someone else's email cannot use the link.
        String intruder = newEmail();
        String intruderChallenge = verifiedNewEmail(intruder);
        assertThat(problem(call(post("/api/core/auth/staff/accept"), Map.of("challengeId", intruderChallenge,
                "inviteToken", token, "acceptTerms", true, "confirmAdult", true), null), 422))
                .isEqualTo("invalid-request");

        String challenge = verifiedNewEmail(email);
        body(call(post("/api/core/auth/staff/accept"), Map.of("challengeId", challenge, "inviteToken", token,
                "acceptTerms", true, "confirmAdult", true), null), 200);
        String second = verifiedNewEmail(newEmail());
        assertThat(problem(call(post("/api/core/auth/staff/accept"), Map.of("challengeId", second, "inviteToken",
                token, "acceptTerms", true, "confirmAdult", true), null), 422)).isEqualTo("invalid-request");
    }

    @Test
    void staffSessionsLastTwelveHours() throws Exception {
        Staff st = createStaff("OPS");
        clock.advance(Duration.ofHours(11));
        assertThat(call(get("/api/core/auth/me"), null, st.session).getStatus()).isEqualTo(200);
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(call(get("/api/core/auth/me"), null, st.session).getStatus()).isEqualTo(401);
    }

    // ---- contact change ---------------------------------------------------------------------------------------------

    @Test
    void phoneChangeNeedsBothCodesAndLogsOutOtherDevices() throws Exception {
        String oldPhone = newPhone();
        Session device1 = signUpCreator(oldPhone);
        Session device2 = login(oldPhone);
        String newPhone = newPhone();
        JsonNode started = body(call(post("/api/core/account/contact-change"), Map.of("newContact", newPhone),
                device1), 202);
        String oldCode = code(oldPhone);
        String newCode = code(newPhone);
        Map<String, Object> wrong = Map.of("oldChallengeId", started.get("oldChallengeId").asString(), "oldCode",
                oldCode, "newChallengeId", started.get("newChallengeId").asString(), "newCode",
                newCode.equals("000000") ? "111111" : "000000");
        assertThat(problem(call(post("/api/core/account/contact-change/confirm"), wrong, device1), 400))
                .isEqualTo("invalid-code");
        MockHttpServletResponse r = call(post("/api/core/account/contact-change/confirm"), Map.of("oldChallengeId",
                started.get("oldChallengeId").asString(), "oldCode", oldCode, "newChallengeId",
                started.get("newChallengeId").asString(), "newCode", newCode), device1);
        Session fresh = session(r);
        assertThat(call(get("/api/core/auth/me"), null, device2).getStatus()).isEqualTo(401);
        assertThat(call(get("/api/core/auth/me"), null, device1).getStatus()).isEqualTo(401);
        assertThat(body(call(get("/api/core/auth/me"), null, fresh), 200).get("user").get("phone").asString())
                .isEqualTo(newPhone);
        login(newPhone);
        clock.advance(Duration.ofMinutes(16)); // the old number already had 3 codes in this window
        JsonNode sent = send("PHONE", oldPhone);
        assertThat(body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId",
                sent.get("challengeId").asString(), "code", code(oldPhone)), null), 200).get("next").asString())
                .as("the old number is free again").isEqualTo("SIGNUP_DETAILS_REQUIRED");
    }

    @Test
    void aNumberThatBelongsToSomeoneElseCannotBeTaken() throws Exception {
        String mine = newPhone();
        String theirs = newPhone();
        Session s = signUpCreator(mine);
        signUpCreator(theirs);
        JsonNode started = body(call(post("/api/core/account/contact-change"), Map.of("newContact", theirs), s), 202);
        assertThat(problem(call(post("/api/core/account/contact-change/confirm"), Map.of("oldChallengeId",
                started.get("oldChallengeId").asString(), "oldCode", code(mine), "newChallengeId",
                started.get("newChallengeId").asString(), "newCode", code(theirs)), s), 409)).isEqualTo("contact-taken");
    }

    @Test
    void opsChangesALostNumberAfterAManualCheck() throws Exception {
        String lost = newPhone();
        Session s = signUpCreator(lost);
        UUID creator = userId(lost);
        String replacement = newPhone();
        JsonNode changed = body(ops(post("/api/core/ops/accounts/" + creator + "/contact"), "OPS",
                UUID.randomUUID().toString(), Map.of("newContact", replacement, "reason",
                        "Aadhaar-matched video call; old SIM lost")), 200);
        assertThat(changed.get("phoneVerified").asBoolean()).isFalse();
        assertThat(call(get("/api/core/auth/me"), null, s).getStatus()).isEqualTo(401);
        login(replacement);
        assertThat(jdbc.sql("SELECT phone_verified_at IS NOT NULL FROM users WHERE id = :id").param("id", creator)
                .query(Boolean.class).single()).as("verified by the first login").isTrue();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    static final class Staff {
        String email;
        UUID userId;
        String secret;
        List<String> recoveryCodes = new ArrayList<>();
        Session session;
    }

    private Staff createStaff(String role) throws Exception {
        Staff st = new Staff();
        st.email = newEmail();
        JsonNode invite = body(ops(post("/api/core/ops/staff/invites"), "FOUNDER", UUID.randomUUID().toString(),
                Map.of("email", st.email, "role", role)), 201);
        String link = invite.get("link").asString();
        String challenge = verifiedNewEmail(st.email);
        MockHttpServletResponse r = call(post("/api/core/auth/staff/accept"), Map.of("challengeId", challenge,
                "inviteToken", link.substring(link.indexOf('#') + 1), "acceptTerms", true, "confirmAdult", true), null);
        JsonNode enrol = body(r, 200);
        assertThat(enrol.get("next").asString()).isEqualTo("MFA_ENROL_REQUIRED");
        assertThat(enrol.get("otpauthUri").asString()).startsWith("otpauth://totp/");
        st.secret = enrol.get("totpSecret").asString();
        Session pending = session(r);
        MockHttpServletResponse done = call(post("/api/core/auth/mfa/enrol"), Map.of("code", totp(st.secret)),
                pending);
        JsonNode d = body(done, 200);
        d.get("recoveryCodes").forEach(c -> st.recoveryCodes.add(c.asString()));
        st.userId = UUID.fromString(d.get("user").get("id").asString());
        st.session = session(done);
        return st;
    }

    /** Email OTP for an existing staff account; returns the pending (MFA) session. */
    private Session emailLogin(String email, String expectedNext) throws Exception {
        JsonNode sent = send("EMAIL", email);
        MockHttpServletResponse r = call(post("/api/core/auth/otp/verify"), Map.of("challengeId",
                sent.get("challengeId").asString(), "code", code(email)), null);
        assertThat(body(r, 200).get("next").asString()).isEqualTo(expectedNext);
        return session(r);
    }

    private String verifiedNewEmail(String email) throws Exception {
        JsonNode sent = send("EMAIL", email);
        String id = sent.get("challengeId").asString();
        assertThat(body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", code(email)),
                null), 200).get("next").asString()).isEqualTo("SIGNUP_DETAILS_REQUIRED");
        return id;
    }

    private Session signUpCreator(String phone) throws Exception {
        JsonNode sent = send("PHONE", phone);
        String id = sent.get("challengeId").asString();
        body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", code(phone)), null), 200);
        return session(call(post("/api/core/auth/signup"), Map.of("challengeId", id, "accountType", "CREATOR",
                "acceptTerms", true, "confirmAdult", true), null));
    }

    private String signUpCreatorPhone() throws Exception {
        String phone = newPhone();
        signUpCreator(phone);
        return phone;
    }

    private Session login(String phone) throws Exception {
        JsonNode sent = send("PHONE", phone);
        MockHttpServletResponse r = call(post("/api/core/auth/otp/verify"), Map.of("challengeId",
                sent.get("challengeId").asString(), "code", code(phone)), null);
        assertThat(body(r, 200).get("next").asString()).isEqualTo("DONE");
        return session(r);
    }

    private UUID userId(String phone) {
        return jdbc.sql("SELECT id FROM users WHERE phone = :p").param("p", phone).query(UUID.class).single();
    }

    private JsonNode send(String channel, String destination) throws Exception {
        return body(call(post("/api/core/auth/otp/send"), Map.of("channel", channel, "destination", destination),
                null), 202);
    }

    private String code(String destination) {
        return messages.last(destination).orElseThrow().text().substring(0, 6);
    }

    private String totp(String base32) {
        return Totp.code(base32Decode(base32), Totp.step(clock.instant()));
    }

    private MockHttpServletResponse ops(MockHttpServletRequestBuilder b, String role, String userId, Object body)
            throws Exception {
        b = withIp(b).header("X-Dev-Role", role).header("X-Dev-User-Id", userId);
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b, Object body, Session s) throws Exception {
        b = withIp(b);
        if (s != null) {
            b = b.cookie(new Cookie(COOKIE, s.token()));
            if (s.csrf() != null) {
                b = b.header("X-CSRF-Token", s.csrf());
            }
        }
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder b) {
        return b.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private Session session(MockHttpServletResponse r) throws Exception {
        JsonNode b = json.readTree(r.getContentAsString());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        Cookie c = r.getCookie(COOKIE);
        assertThat(c).as("session cookie").isNotNull();
        return new Session(c.getValue(), b.get("csrfToken").asString());
    }

    private JsonNode body(MockHttpServletResponse r, int status) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        return json.readTree(r.getContentAsString());
    }

    /** The problem type's last path segment. */
    private String problem(MockHttpServletResponse r, int status) throws Exception {
        String type = body(r, status).get("type").asString();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private static String newPhone() {
        return "+9198" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String newEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 12) + "@brand.in";
    }

    private static int rnd(int n) {
        return ThreadLocalRandom.current().nextInt(n);
    }

    static byte[] base32Decode(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char ch : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(ch);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
