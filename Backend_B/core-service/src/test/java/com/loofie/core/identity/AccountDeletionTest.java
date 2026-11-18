package com.loofie.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.identity.infra.LoggingMessageSender;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.messaging.OutboxRelay;
import com.loofie.core.storage.ObjectStore;
import com.loofie.core.storage.UploadCleanup;
import com.loofie.core.support.FakeAiService;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F1 round C part 2: account deletion over HTTP + Postgres. Fresh code required; everything personal goes in one
 * commit (account, passport, numbers, screenshots, files, Instagram data at Backend A); blockers answer 409 with the
 * reason; ops deletion needs a senior role and a reason; the number can sign up again.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AccountDeletionTest {

    static final String COOKIE = "loofie_session";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired LoggingMessageSender messages;
    @Autowired JdbcClient jdbc;
    @Autowired FakeAiService fakeAi;
    @Autowired OutboxRelay relay;
    @Autowired UploadCleanup cleanup;
    @Autowired ObjectStore store;
    @Autowired JobFixtures fixtures;

    private String ip;

    record Session(String token, String csrf, UUID userId) {
    }

    @BeforeEach
    void freshNetwork() {
        ip = "10." + rnd(250) + "." + rnd(250) + "." + rnd(250);
        fakeAi.resetCreatorSide();
    }

    @Test
    void aCreatorDeletesHerAccountWithAFreshCodeAndEverythingPersonalGoes() throws Exception {
        String phone = newPhone();
        Session s = creatorSignup(phone);
        call(put("/api/core/creator/profile"), passport(), s);
        String key = upload(s);
        assertThat(call(post("/api/core/creator/evidence"), Map.of("uploadKey", key, "kind", "INSIGHTS"), s)
                .getStatus()).isEqualTo(201);
        call(put("/api/core/account/consents/marketing"), Map.of("granted", true), s);
        assertThat(store.stat(key)).isPresent();

        assertThat(call(post("/api/core/account/delete/start"), null, null).getStatus()).isEqualTo(401);
        JsonNode started = body(call(post("/api/core/account/delete/start"), null, s), 202);
        assertThat(started.get("sentTo").asString()).doesNotContain(phone.substring(4, 10));
        String challenge = started.get("challengeId").asString();
        String code = messages.last(phone).orElseThrow().text().substring(0, 6);

        // wrong code: counted, nothing deleted
        assertThat(problem(call(post("/api/core/account/delete"), Map.of("challengeId", challenge, "code",
                wrong(code)), s), 400)).isEqualTo("invalid-code");
        assertThat(status(s.userId())).isEqualTo("ACTIVE");
        // without CSRF: refused
        assertThat(call(post("/api/core/account/delete"), Map.of("challengeId", challenge, "code", code), s, false)
                .getStatus()).isEqualTo(403);

        MockHttpServletResponse done = call(post("/api/core/account/delete"), Map.of("challengeId", challenge, "code",
                code), s);
        assertThat(done.getStatus()).isEqualTo(204);
        assertThat(done.getCookie(COOKIE).getMaxAge()).isZero();
        assertThat(call(get("/api/core/auth/me"), null, s).getStatus()).isEqualTo(401);

        // the account says nothing about her any more
        Map<String, Object> u = jdbc.sql("SELECT status, phone, email, status_reason FROM users WHERE id = :id")
                .param("id", s.userId()).query().singleRow();
        assertThat(u.get("status")).isEqualTo("DELETED");
        assertThat(u.get("phone")).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM sessions WHERE user_id = :id AND revoked_at IS NULL")
                .param("id", s.userId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM consents WHERE user_id = :id AND purpose = 'MARKETING' AND "
                + "withdrawn_at IS NULL").param("id", s.userId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM otp_challenges WHERE destination = :p").param("p", phone)
                .query(Long.class).single()).isZero();
        Map<String, Object> p = jdbc.sql("SELECT display_name, city, bio, instagram_handle, marketplace_status, "
                + "cardinality(sample_links) AS links FROM creator_profiles WHERE user_id = :id")
                .param("id", s.userId()).query().singleRow();
        assertThat(p.get("display_name")).isNull();
        assertThat(p.get("city")).isNull();
        assertThat(p.get("bio")).isNull();
        assertThat(p.get("instagram_handle")).isNull();
        assertThat(p.get("marketplace_status")).isEqualTo("HIDDEN");
        assertThat(p.get("links")).isEqualTo(0);
        assertThat(jdbc.sql("SELECT count(*) FROM creator_metrics WHERE creator_id = :id").param("id", s.userId())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM creator_evidence WHERE creator_id = :id").param("id", s.userId())
                .query(String.class).list()).containsOnly("REMOVED");
        assertThat(audit(s.userId(), "ACCOUNT_DELETED")).isEqualTo(1);

        // Backend A is asked to delete her Instagram data (through the outbox)
        for (int i = 0; i < 20 && relay.publishBatch() > 0; i++) {
            // drain
        }
        assertThat(fakeAi.creatorCalls().stream().anyMatch(c -> c.method().equals("DELETE")
                && c.path().equals("/ai/instagram/accounts/" + s.userId()) && "delete_data=true".equals(c.query())))
                .isTrue();

        // her file leaves storage on the next cleanup
        cleanup.runOnce();
        assertThat(store.stat(key)).isEmpty();
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM uploads WHERE key = :k").param("k", key)
                .query(Boolean.class).single()).isTrue();

        // the number is free again: a new, separate account
        Session again = creatorSignup(phone);
        assertThat(again.userId()).isNotEqualTo(s.userId());
    }

    @Test
    void aCodeForSomethingElseCannotDeleteTheAccount() throws Exception {
        String phone = newPhone();
        Session s = creatorSignup(phone);
        // a login code for the same phone
        JsonNode login = body(call(post("/api/core/auth/otp/send"), Map.of("channel", "PHONE", "destination", phone),
                null), 202);
        String code = messages.last(phone).orElseThrow().text().substring(0, 6);
        assertThat(problem(call(post("/api/core/account/delete"), Map.of("challengeId", login.get("challengeId")
                .asString(), "code", code), s), 400)).isEqualTo("code-expired");
        assertThat(status(s.userId())).isEqualTo("ACTIVE");
    }

    @Test
    void aCreatorWithAJobInProgressCannotDeleteYet() throws Exception {
        Session s = creatorSignup(newPhone());
        JobFixtures.Seeded job = fixtures.jobIn(JobStatus.PAYOUT_PENDING);
        jdbc.sql("UPDATE assignments SET creator_id = :c WHERE job_id = :j").param("c", s.userId())
                .param("j", job.jobId()).update();
        MockHttpServletResponse r = call(post("/api/core/account/delete/start"), null, s);
        JsonNode b = body(r, 409);
        assertThat(b.get("type").asString()).endsWith("/deletion-blocked");
        assertThat(b.get("detail").asString()).contains("payouts");
        assertThat(b.get("jobIds").get(0).asString()).isEqualTo(job.jobId().toString());
    }

    @Test
    void theOnlyAdminOfATeamMustHandOverFirstAndTheLastMemberMustFinishJobs() throws Exception {
        String adminEmail = newEmail();
        Session admin = brandSignup(adminEmail);
        JsonNode company = body(call(post("/api/core/brand/company"), companyForm(), admin), 201);
        UUID org = UUID.fromString(company.get("company").get("id").asString());
        String memberEmail = newEmail();
        Session member = join(admin, memberEmail);

        assertThat(problem(call(post("/api/core/account/delete/start"), null, admin), 409))
                .isEqualTo("deletion-blocked");
        // hand over: the member becomes admin, then the first admin can go
        assertThat(call(put("/api/core/brand/team/members/" + member.userId() + "/role"), Map.of("role",
                "BRAND_ADMIN"), admin).getStatus()).isEqualTo(204);
        deleteWithCode(admin, adminEmail);
        assertThat(status(admin.userId())).isEqualTo("DELETED");
        assertThat(jdbc.sql("SELECT count(*) FROM org_members WHERE org_id = :o").param("o", org).query(Long.class)
                .single()).isEqualTo(1);
        assertThat(body(call(get("/api/core/auth/me"), null, member), 200).get("user").get("role").asString())
                .isEqualTo("BRAND_ADMIN");
        assertThat(jdbc.sql("SELECT target_org_id FROM audit_log WHERE target_user_id = :u AND action = "
                + "'ACCOUNT_DELETED'").param("u", admin.userId()).query(UUID.class).single()).isEqualTo(org);

        // now the last member, with a job in progress
        JobFixtures.Seeded job = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        jdbc.sql("UPDATE jobs SET brand_org_id = :o WHERE id = :j").param("o", org).param("j", job.jobId()).update();
        JsonNode blocked = body(call(post("/api/core/account/delete/start"), null, member), 409);
        assertThat(blocked.get("jobIds").get(0).asString()).isEqualTo(job.jobId().toString());
        jdbc.sql("UPDATE jobs SET status = 'CLOSED' WHERE id = :j").param("j", job.jobId()).update();
        deleteWithCode(member, memberEmail);
        assertThat(status(member.userId())).isEqualTo("DELETED");
        // the company and its records stay (invoices, jobs), with nobody in it
        assertThat(jdbc.sql("SELECT count(*) FROM organizations WHERE id = :o").param("o", org).query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    void opsDeletionNeedsASeniorRoleAndAReason() throws Exception {
        Session s = creatorSignup(newPhone());
        String path = "/api/core/ops/accounts/" + s.userId() + "/delete";
        assertThat(problem(ops(post(path), Map.of("reason", "Grievance #12"), "OPS"), 403))
                .isEqualTo("actor-not-allowed");
        assertThat(problem(ops(post(path), Map.of("reason", " "), "OPS_LEAD"), 422)).isEqualTo("invalid-request");
        assertThat(ops(post(path), Map.of("reason", "Grievance #12: asked by email"), "OPS_LEAD").getStatus())
                .isEqualTo(204);
        assertThat(status(s.userId())).isEqualTo("DELETED");
        assertThat(audit(s.userId(), "ACCOUNT_DELETED_BY_OPS")).isEqualTo(1);
        assertThat(call(get("/api/core/auth/me"), null, s).getStatus()).isEqualTo(401);
        // already deleted: nothing left to delete
        assertThat(problem(ops(post(path), Map.of("reason", "again"), "FOUNDER"), 404)).isEqualTo("account-not-found");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void deleteWithCode(Session s, String contact) throws Exception {
        String challenge = body(call(post("/api/core/account/delete/start"), null, s), 202).get("challengeId")
                .asString();
        String code = messages.last(contact).orElseThrow().text().substring(0, 6);
        assertThat(call(post("/api/core/account/delete"), Map.of("challengeId", challenge, "code", code), s)
                .getStatus()).isEqualTo(204);
    }

    private String upload(Session s) throws Exception {
        JsonNode t = body(call(post("/api/core/creator/uploads"), Map.of("contentType", "image/png"), s), 201);
        assertThat(mvc.perform(put(URI.create(t.get("url").asString())).header("Content-Type", "image/png")
                .content(new byte[1200])).andReturn().getResponse().getStatus()).isEqualTo(200);
        return t.get("key").asString();
    }

    private static Map<String, Object> passport() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("displayName", "Delete Me");
        f.put("city", "Pune");
        f.put("state", "MH");
        f.put("languages", List.of("Hindi"));
        f.put("categories", List.of("beauty_skincare"));
        f.put("formats", List.of("REEL"));
        f.put("ratePerReelRupees", 2000);
        f.put("partnershipAds", false);
        f.put("turnaroundDays", 4);
        f.put("bio", "To be erased");
        f.put("sampleLinks", List.of("https://www.instagram.com/reel/DEL/"));
        f.put("instagramHandle", "delete.me." + rnd(99999));
        f.put("followers", 5000);
        f.put("avgViews", 1200);
        return f;
    }

    private static Map<String, Object> companyForm() {
        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("line1", "1 Test Road");
        billing.put("city", "Mumbai");
        billing.put("state", "MH");
        billing.put("pincode", "400001");
        return Map.of("legalName", "Delete Test Pvt Ltd", "billing", billing, "pickupState", "MH");
    }

    private Session join(Session admin, String email) throws Exception {
        JsonNode inv = body(call(post("/api/core/brand/team/invites"), Map.of("email", email, "role", "BRAND_MEMBER"),
                admin), 201);
        String link = inv.get("link").asString();
        String challenge = verified("EMAIL", email);
        return session(call(post("/api/core/auth/invites/accept"), Map.of("challengeId", challenge, "inviteToken",
                link.substring(link.indexOf('#') + 1), "acceptTerms", true, "confirmAdult", true), null));
    }

    private Session brandSignup(String email) throws Exception {
        String challenge = verified("EMAIL", email);
        return session(call(post("/api/core/auth/signup"), Map.of("challengeId", challenge, "accountType", "BRAND",
                "acceptTerms", true, "confirmAdult", true), null));
    }

    private Session creatorSignup(String phone) throws Exception {
        String challenge = verified("PHONE", phone);
        return session(call(post("/api/core/auth/signup"), Map.of("challengeId", challenge, "accountType", "CREATOR",
                "acceptTerms", true, "confirmAdult", true), null));
    }

    private String verified(String channel, String destination) throws Exception {
        JsonNode sent = body(call(post("/api/core/auth/otp/send"), Map.of("channel", channel, "destination",
                destination), null), 202);
        String id = sent.get("challengeId").asString();
        String code = messages.last(destination).orElseThrow().text().substring(0, 6);
        assertThat(body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", code), null), 200)
                .get("next").asString()).isEqualTo("SIGNUP_DETAILS_REQUIRED");
        return id;
    }

    private String status(UUID userId) {
        return jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", userId).query(String.class).single();
    }

    private long audit(UUID target, String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE target_user_id = :t AND action = :a").param("t", target)
                .param("a", action).query(Long.class).single();
    }

    private MockHttpServletResponse ops(MockHttpServletRequestBuilder b, Object body, String role) throws Exception {
        return mvc.perform(withIp(b).header("X-Dev-Role", role).header("X-Dev-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn()
                .getResponse();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b, Object body, Session s) throws Exception {
        return call(b, body, s, true);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b, Object body, Session s, boolean csrf)
            throws Exception {
        b = withIp(b);
        if (s != null) {
            b = b.cookie(new Cookie(COOKIE, s.token()));
            if (csrf) {
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
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        Cookie c = r.getCookie(COOKIE);
        JsonNode j = json.readTree(r.getContentAsString());
        return new Session(c.getValue(), j.get("csrfToken").asString(), UUID.fromString(j.get("user").get("id")
                .asString()));
    }

    private JsonNode body(MockHttpServletResponse r, int status) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        return json.readTree(r.getContentAsString());
    }

    private String problem(MockHttpServletResponse r, int status) throws Exception {
        String type = body(r, status).get("type").asString();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private static String newPhone() {
        return "+9196" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String newEmail() {
        return "d" + UUID.randomUUID().toString().substring(0, 12) + "@brand.in";
    }

    private static int rnd(int n) {
        return ThreadLocalRandom.current().nextInt(n);
    }
}
