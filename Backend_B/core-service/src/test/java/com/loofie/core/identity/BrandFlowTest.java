package com.loofie.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.loofie.core.calendar.app.HolidayCalendar;
import com.loofie.core.calendar.infra.HolidayRepository;
import com.loofie.core.common.Ids;
import com.loofie.core.identity.app.BrandDirectory;
import com.loofie.core.identity.app.TeamService;
import com.loofie.core.identity.infra.LoggingMessageSender;
import com.loofie.core.job.app.JobIntakeService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobDeadlines;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F1 round B over HTTP against PostgreSQL: company setup, brand roles in the session, teammate invites, the
 * at-least-one-admin rule (including a race), GSTIN rules, cross-brand isolation, and the pickup state reaching the
 * brand's shipping clock.
 */
@IntegrationTest
@AutoConfigureMockMvc
class BrandFlowTest {

    static final String COOKIE = "loofie_session";
    /** Valid GSTINs (check character computed): see GstinTest. */
    static final String GSTIN_A = "27AAPFU0939F1ZV";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired LoggingMessageSender messages;
    @Autowired JdbcClient jdbc;
    @Autowired TeamService team;
    @Autowired BrandDirectory brands;
    @Autowired JobIntakeService intake;
    @Autowired TransactionTemplate tx;
    @Autowired HolidayCalendar calendar;
    @Autowired HolidayRepository holidays;
    @Autowired JobFixtures fixtures;

    private String ip;

    record Session(String token, String csrf) {
    }

    record Brand(String email, Session session, UUID userId, UUID orgId) {
    }

    @BeforeEach
    void setUp() {
        ip = "10." + rnd(250) + "." + rnd(250) + "." + rnd(250);
        messages.failing(false);
        jdbc.sql("UPDATE organizations SET gstin = NULL, gstin_shared_reason = NULL WHERE gstin = :g")
                .param("g", GSTIN_A).update();
    }

    @Test
    void aBrandSetsUpItsCompanyAndActsWithItsCookieAlone() throws Exception {
        Brand b = brandWithCompany("MH", null);
        JsonNode me = body(call(get("/api/core/auth/me"), null, b.session()), 200);
        assertThat(me.get("user").get("role").asString()).isEqualTo("BRAND_ADMIN");
        assertThat(me.get("user").get("orgId").asString()).isEqualTo(b.orgId().toString());
        assertThat(me.get("user").get("onboardingCompleted").asBoolean()).isTrue();
        assertThat(call(get("/api/core/notifications"), null, b.session()).getStatus()).isEqualTo(200);
        JsonNode company = body(call(get("/api/core/brand/company"), null, b.session()), 200);
        assertThat(company.get("company").get("pickupState").asString()).isEqualTo("MH");
        assertThat(company.get("company").get("gstin").isNull()).isTrue();
        assertThat(company.get("readyForBriefs").asBoolean()).as("GSTIN is optional: B2C invoices").isTrue();
        assertThat(brands.readyForBriefs(b.orgId())).as("no GSTIN, still ready").isTrue();
        assertThat(brands.readyForBriefs(UUID.randomUUID())).as("no company, not ready").isFalse();
        assertThat(company.get("members")).hasSize(1);
        // A second company for the same person is refused.
        assertThat(problem(call(post("/api/core/brand/company"), companyForm("KA", null), b.session()), 422))
                .isEqualTo("invalid-request");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'ORG_CREATED' AND target_org_id = :o")
                .param("o", b.orgId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void companyFormIsChecked() throws Exception {
        Session s = brandSignup(newEmail());
        Map<String, Object> badPin = companyForm("MH", null);
        ((Map<String, Object>) badPin.get("billing")).put("pincode", "01234");
        assertThat(problem(call(post("/api/core/brand/company"), badPin, s), 422)).isEqualTo("invalid-request");
        assertThat(problem(call(post("/api/core/brand/company"), companyForm("XX", null), s), 422))
                .isEqualTo("invalid-request");
        assertThat(problem(call(post("/api/core/brand/company"), companyForm("MH", "27AAPFU0939F1ZW"), s), 422))
                .as("wrong check character").isEqualTo("invalid-request");
        assertThat(problem(call(post("/api/core/brand/company"), companyForm("MH", "27AAPFU0939F1Z"), s), 422))
                .isEqualTo("invalid-request");
        Map<String, Object> noName = companyForm("MH", null);
        noName.put("legalName", " ");
        assertThat(problem(call(post("/api/core/brand/company"), noName, s), 422)).isEqualTo("invalid-request");
        assertThat(call(post("/api/core/brand/company"), companyForm("mh", " 27aapfu0939f1zv "), s).getStatus())
                .as("lower case and spaces are fine").isEqualTo(201);
    }

    @Test
    void oneGstinPerCompanyUnlessOpsAllowsAShare() throws Exception {
        Brand a = brandWithCompany("MH", GSTIN_A);
        assertThat(brands.readyForBriefs(a.orgId())).isTrue();
        Session s = brandSignup(newEmail());
        assertThat(problem(call(post("/api/core/brand/company"), companyForm("MH", GSTIN_A), s), 409))
                .isEqualTo("gstin-taken");
        UUID b = UUID.fromString(body(call(post("/api/core/brand/company"), companyForm("MH", null), s), 201)
                .get("company").get("id").asString());
        assertThat(problem(call(put("/api/core/brand/company/gstin"), Map.of("gstin", GSTIN_A), s), 409))
                .isEqualTo("gstin-taken");
        assertThat(problem(ops(put("/api/core/ops/organizations/" + b + "/gstin"), Map.of("gstin", GSTIN_A,
                "shared", false, "reason", "Same company, second brand"), "OPS"), 409)).isEqualTo("gstin-taken");
        JsonNode shared = body(ops(put("/api/core/ops/organizations/" + b + "/gstin"), Map.of("gstin", GSTIN_A,
                "shared", true, "reason", "Same company, second brand (checked certificate)"), "OPS"), 200);
        assertThat(shared.get("company").get("gstinSharedReason").asString()).contains("second brand");
        assertThat(brands.readyForBriefs(b)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'GSTIN_SET_BY_OPS' AND target_org_id = :o")
                .param("o", b).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theAdminSetsTheGstinOnceThenOnlyOpsChangesIt() throws Exception {
        Brand a = brandWithCompany("KA", null);
        body(call(put("/api/core/brand/company/gstin"), Map.of("gstin", GSTIN_A), a.session()), 200);
        assertThat(problem(call(put("/api/core/brand/company/gstin"), Map.of("gstin", GSTIN_A), a.session()), 422))
                .isEqualTo("invalid-request");
    }

    @Test
    void aNewPersonJoinsByInviteAsAMemberWithMemberRights() throws Exception {
        Brand admin = brandWithCompany("DL", null);
        String email = newEmail();
        String token = invite(admin, email, "BRAND_MEMBER");
        String challenge = verifiedNewEmail(email);
        MockHttpServletResponse r = call(post("/api/core/auth/invites/accept"), Map.of("challengeId", challenge,
                "inviteToken", token, "acceptTerms", true, "confirmAdult", true), null);
        JsonNode joined = body(r, 200);
        assertThat(joined.get("next").asString()).isEqualTo("DONE");
        assertThat(joined.get("user").get("role").asString()).isEqualTo("BRAND_MEMBER");
        assertThat(joined.get("user").get("orgId").asString()).isEqualTo(admin.orgId().toString());
        Session member = session(r);
        assertThat(body(call(get("/api/core/brand/company"), null, member), 200).get("members")).hasSize(2);
        assertThat(problem(call(post("/api/core/brand/team/invites"), Map.of("email", newEmail(), "role",
                "BRAND_MEMBER"), member), 403)).isEqualTo("actor-not-allowed");
        assertThat(problem(call(put("/api/core/brand/company"), companyForm("DL", null), member), 403))
                .isEqualTo("actor-not-allowed");
        assertThat(call(get("/api/core/notifications"), null, member).getStatus()).isEqualTo(200);
    }

    @Test
    void someoneWithABrandAccountAcceptsWhileLoggedIn() throws Exception {
        Brand admin = brandWithCompany("TN", null);
        String email = newEmail();
        Session loner = brandSignup(email);
        assertThat(problem(call(get("/api/core/notifications"), null, loner), 403)).isEqualTo("actor-not-allowed");
        String token = invite(admin, email, "BRAND_ADMIN");
        body(call(post("/api/core/brand/invites/accept"), Map.of("inviteToken", token), loner), 200);
        JsonNode me = body(call(get("/api/core/auth/me"), null, loner), 200);
        assertThat(me.get("user").get("role").asString()).as("same session, new role").isEqualTo("BRAND_ADMIN");
        assertThat(call(get("/api/core/notifications"), null, loner).getStatus()).isEqualTo(200);
    }

    @Test
    void inviteRules() throws Exception {
        Brand admin = brandWithCompany("GJ", null);
        Brand other = brandWithCompany("GJ", null);
        assertThat(problem(call(post("/api/core/brand/team/invites"), Map.of("email", other.email(), "role",
                "BRAND_MEMBER"), admin.session()), 409)).as("already in another company").isEqualTo("contact-taken");
        assertThat(problem(call(post("/api/core/brand/team/invites"), Map.of("email", newEmail(), "role", "OPS"),
                admin.session()), 422)).isEqualTo("invalid-request");

        String email = newEmail();
        String first = invite(admin, email, "BRAND_MEMBER");
        String second = invite(admin, email, "BRAND_MEMBER"); // replaces the first
        Session wrong = brandSignup(newEmail());
        assertThat(problem(call(post("/api/core/brand/invites/accept"), Map.of("inviteToken", second), wrong), 422))
                .as("bound to the invited email").isEqualTo("invalid-request");
        String challenge = verifiedNewEmail(email);
        assertThat(problem(call(post("/api/core/auth/invites/accept"), Map.of("challengeId", challenge,
                "inviteToken", first, "acceptTerms", true, "confirmAdult", true), null), 422))
                .as("the older invite was replaced").isEqualTo("invalid-request");

        JsonNode company = body(call(get("/api/core/brand/company"), null, admin.session()), 200);
        String inviteId = company.get("pendingInvites").get(0).get("id").asString();
        assertThat(call(delete("/api/core/brand/team/invites/" + inviteId), null, admin.session()).getStatus())
                .isEqualTo(204);
        assertThat(problem(call(post("/api/core/auth/invites/accept"), Map.of("challengeId", challenge,
                "inviteToken", second, "acceptTerms", true, "confirmAdult", true), null), 422))
                .as("revoked").isEqualTo("invalid-request");
    }

    @Test
    void aCompanyAlwaysHasAnAdminAndRemovalWorksOnTheNextRequest() throws Exception {
        Brand admin = brandWithCompany("WB", null);
        assertThat(problem(call(post("/api/core/brand/team/leave"), null, admin.session()), 422))
                .isEqualTo("invalid-request");
        assertThat(problem(call(put("/api/core/brand/team/members/" + admin.userId() + "/role"),
                Map.of("role", "BRAND_MEMBER"), admin.session()), 422)).isEqualTo("invalid-request");

        Brand second = joinAs(admin, "BRAND_ADMIN");
        assertThat(call(put("/api/core/brand/team/members/" + admin.userId() + "/role"), Map.of("role",
                "BRAND_MEMBER"), second.session()).getStatus()).isEqualTo(204);
        assertThat(problem(call(post("/api/core/brand/team/invites"), Map.of("email", newEmail(), "role",
                "BRAND_MEMBER"), admin.session()), 403)).as("demotion works on the next request")
                .isEqualTo("actor-not-allowed");

        assertThat(call(delete("/api/core/brand/team/members/" + admin.userId()), null, second.session())
                .getStatus()).isEqualTo(204);
        assertThat(problem(call(get("/api/core/notifications"), null, admin.session()), 403))
                .isEqualTo("actor-not-allowed");
        JsonNode me = body(call(get("/api/core/auth/me"), null, admin.session()), 200);
        assertThat(me.get("user").get("orgId").isNull()).isTrue();
        assertThat(me.get("user").get("onboardingCompleted").asBoolean()).as("shows the company form again")
                .isFalse();
        assertThat(jdbc.sql("SELECT string_agg(action, ',' ORDER BY at) FROM audit_log WHERE target_org_id = :o")
                .param("o", admin.orgId()).query(String.class).single())
                .contains("MEMBER_INVITED", "MEMBER_JOINED", "MEMBER_ROLE_CHANGED", "MEMBER_REMOVED");
    }

    @Test
    void twoAdminsDemotingEachOtherAtOnceLeaveOneAdmin() throws Exception {
        Brand a = brandWithCompany("RJ", null);
        Brand b = joinAs(a, "BRAND_ADMIN");
        Actor actorA = Actor.brandAdmin(a.userId(), a.orgId());
        Actor actorB = Actor.brandAdmin(b.userId(), b.orgId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        results.add(pool.submit(() -> demote(go, actorA, b.userId())));
        results.add(pool.submit(() -> demote(go, actorB, a.userId())));
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) {
            ok += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM org_members WHERE org_id = :o AND role = 'BRAND_ADMIN'")
                .param("o", a.orgId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void oneBrandCannotSeeAnothersJob() throws Exception {
        Brand a = brandWithCompany("MH", null);
        JobFixtures.Seeded otherBrandsJob = fixtures.jobIn(JobStatus.PRODUCT_SHIPPED);
        assertThat(problem(call(get("/api/core/jobs/" + otherBrandsJob.jobId() + "/delivery"), null, a.session()),
                404)).isEqualTo("job-not-found");
    }

    @Test
    void newJobsCarryThePickupStateAndItsHolidaysMoveTheShipDeadline() throws Exception {
        Brand b = brandWithCompany("WB", null);
        UUID orderId = Ids.newId();
        jdbc.sql("INSERT INTO orders (id, brand_org_id, created_at, version) VALUES (:id, :org, now(), 0)")
                .param("id", orderId).param("org", b.orgId()).update();
        List<UUID> ids = tx.execute(s -> intake.createJobs(orderId, b.orgId(), Actor.brandAdmin(b.userId(),
                b.orgId()), List.of(new JobIntakeService.NewJob(false, 500000)), Ids.newId()));
        assertThat(jdbc.sql("SELECT brand_pickup_state FROM jobs WHERE id = :id").param("id", ids.get(0))
                .query(String.class).single()).isEqualTo("WB");

        // Monday 7 Mar 2033, 10:00 IST; a WB-only holiday on Tuesday.
        Instant accepted = LocalDate.of(2033, 3, 7).atTime(10, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        tx.executeWithoutResult(s -> holidays.upsert(LocalDate.of(2033, 3, 8), "WB", "Test WB closure",
                UUID.randomUUID(), accepted));
        Job wb = acceptedJob("WB");
        Job mh = acceptedJob("MH");
        Job unknown = acceptedJob(null);
        JobDeadlines.apply(wb, accepted, calendar, null);
        JobDeadlines.apply(mh, accepted, calendar, null);
        JobDeadlines.apply(unknown, accepted, calendar, null);
        assertThat(wb.getShipDueAt()).isEqualTo(mh.getShipDueAt().plus(java.time.Duration.ofDays(1)));
        assertThat(unknown.getShipDueAt()).as("unknown state: national holidays only").isEqualTo(mh.getShipDueAt());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static Job acceptedJob(String state) {
        Job j = Job.forNewOrder(Ids.newId(), Ids.newId(), Ids.newId(), state, false, 500000, Instant.EPOCH);
        j.moveTo(JobStatus.CREATOR_ACCEPTED, Instant.EPOCH);
        return j;
    }

    private boolean demote(CountDownLatch go, Actor actor, UUID other) throws InterruptedException {
        go.await();
        try {
            team.changeRole(actor, other, ActorRole.BRAND_MEMBER);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Brand brandWithCompany(String pickup, String gstin) throws Exception {
        String email = newEmail();
        Session s = brandSignup(email);
        JsonNode c = body(call(post("/api/core/brand/company"), companyForm(pickup, gstin), s), 201);
        UUID user = jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", email).query(UUID.class).single();
        return new Brand(email, s, user, UUID.fromString(c.get("company").get("id").asString()));
    }

    private Brand joinAs(Brand admin, String role) throws Exception {
        String email = newEmail();
        String token = invite(admin, email, role);
        String challenge = verifiedNewEmail(email);
        MockHttpServletResponse r = call(post("/api/core/auth/invites/accept"), Map.of("challengeId", challenge,
                "inviteToken", token, "acceptTerms", true, "confirmAdult", true), null);
        JsonNode j = body(r, 200);
        return new Brand(email, session(r), UUID.fromString(j.get("user").get("id").asString()), admin.orgId());
    }

    private String invite(Brand admin, String email, String role) throws Exception {
        JsonNode inv = body(call(post("/api/core/brand/team/invites"), Map.of("email", email, "role", role),
                admin.session()), 201);
        assertThat(inv.get("emailSent").asBoolean()).isTrue();
        String link = inv.get("link").asString();
        return link.substring(link.indexOf('#') + 1);
    }

    private Session brandSignup(String email) throws Exception {
        String id = verifiedNewEmail(email);
        return session(call(post("/api/core/auth/signup"), Map.of("challengeId", id, "accountType", "BRAND",
                "acceptTerms", true, "confirmAdult", true), null));
    }

    private String verifiedNewEmail(String email) throws Exception {
        JsonNode sent = body(call(post("/api/core/auth/otp/send"), Map.of("channel", "EMAIL", "destination", email),
                null), 202);
        String id = sent.get("challengeId").asString();
        String code = messages.last(email).orElseThrow().text().substring(0, 6);
        assertThat(body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", code), null), 200)
                .get("next").asString()).isEqualTo("SIGNUP_DETAILS_REQUIRED");
        return id;
    }

    private static Map<String, Object> companyForm(String pickup, String gstin) {
        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("line1", "12 Industrial Estate, Andheri East");
        billing.put("city", "Mumbai");
        billing.put("state", "MH");
        billing.put("pincode", "400093");
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("legalName", "Test Brand Pvt Ltd");
        f.put("billing", billing);
        f.put("pickupState", pickup);
        if (gstin != null) {
            f.put("gstin", gstin);
        }
        return f;
    }

    private MockHttpServletResponse ops(MockHttpServletRequestBuilder b, Object body, String role) throws Exception {
        return mvc.perform(withIp(b).header("X-Dev-Role", role).header("X-Dev-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn()
                .getResponse();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b, Object body, Session s) throws Exception {
        b = withIp(b);
        if (s != null) {
            b = b.cookie(new Cookie(COOKIE, s.token())).header("X-CSRF-Token", s.csrf());
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
        assertThat(c).isNotNull();
        return new Session(c.getValue(), json.readTree(r.getContentAsString()).get("csrfToken").asString());
    }

    private JsonNode body(MockHttpServletResponse r, int status) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        return json.readTree(r.getContentAsString());
    }

    private String problem(MockHttpServletResponse r, int status) throws Exception {
        String type = body(r, status).get("type").asString();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private static String newEmail() {
        return "b" + UUID.randomUUID().toString().substring(0, 12) + "@brand.in";
    }

    private static int rnd(int n) {
        return ThreadLocalRandom.current().nextInt(n);
    }
}
