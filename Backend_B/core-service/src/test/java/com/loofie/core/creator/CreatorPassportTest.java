package com.loofie.core.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.net.URI;
import java.util.ArrayList;
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
import com.loofie.core.messaging.OutboxRelay;
import com.loofie.core.support.FakeAiService;
import com.loofie.core.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F1 round C part 1 over HTTP + Postgres with a fake Backend A and the local file store: the manual path with a real
 * signed upload, the Instagram path (never trusting the return URL), upload rules, and the ops verification queue.
 */
@IntegrationTest
@AutoConfigureMockMvc
class CreatorPassportTest {

    static final String COOKIE = "loofie_session";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired LoggingMessageSender messages;
    @Autowired JdbcClient jdbc;
    @Autowired FakeAiService fakeAi;
    @Autowired OutboxRelay relay;

    private String ip;

    record Session(String token, String csrf, UUID userId) {
    }

    @BeforeEach
    void freshNetwork() {
        ip = "10." + rnd(250) + "." + rnd(250) + "." + rnd(250);
        fakeAi.resetCreatorSide();
    }

    // ------------------------------------------------------------------------------------------------ manual path

    @Test
    void manualPathWithARealUploadCompletesThePassportAndReachesBackendA() throws Exception {
        Session s = creatorSignup();
        JsonNode first = body(call(get("/api/core/creator/profile"), null, s), 200);
        assertThat(first.get("completed").asBoolean()).isFalse();
        assertThat(strings(first.get("missing"))).contains("displayName", "followers", "insightsScreenshot");
        assertThat(strings(first.get("options").get("categories"))).contains("beauty_skincare");

        // progress is saved even when incomplete
        JsonNode partial = body(call(put("/api/core/creator/profile"), Map.of("displayName", "Ananya R",
                "city", "Pune"), s), 200);
        assertThat(partial.get("displayName").asString()).isEqualTo("Ananya R");
        assertThat(partial.get("completed").asBoolean()).isFalse();

        JsonNode filled = body(call(put("/api/core/creator/profile"), fullForm("ananya.glows", 12400L, 4200L), s), 200);
        assertThat(strings(filled.get("missing"))).containsExactly("insightsScreenshot");
        assertThat(filled.get("numbers").get(0).get("source").asString()).isEqualTo("SELF_REPORTED");
        assertThat(me(s).get("user").get("onboardingCompleted").asBoolean()).isFalse();

        String key = uploadPng(s, 2048);
        JsonNode done = body(call(post("/api/core/creator/evidence"), Map.of("uploadKey", key, "kind", "INSIGHTS"), s),
                201);
        assertThat(done.get("completed").asBoolean()).isTrue();
        assertThat(done.get("missing")).isEmpty();
        assertThat(done.get("bookable").asBoolean()).isTrue();
        assertThat(done.get("evidence").get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(me(s).get("user").get("onboardingCompleted").asBoolean()).isTrue();
        assertThat(auditCount(s.userId(), "CREATOR_PROFILE_COMPLETED")).isEqualTo(1);

        // the profile goes to Backend A through the outbox, as self-reported numbers
        relayAll();
        JsonNode push = fakeAi.creatorCalls().stream().filter(c -> c.path().equals("/ai/creators/profile")
                        && s.userId().toString().equals(c.body().get("creator_ref").asString()))
                .reduce((a, b) -> b).orElseThrow().body();
        assertThat(push.get("source").asString()).isEqualTo("manual_profile");
        assertThat(push.get("handle").asString()).isEqualTo("ananya.glows");
        assertThat(push.get("followers").asLong()).isEqualTo(12400);
        assertThat(push.get("primary_language").asString()).isEqualTo("Hindi");
    }

    @Test
    void progressSurvivesLoggingOutAndIn() throws Exception {
        String phone = newPhone();
        Session s = creatorSignup(phone);
        call(put("/api/core/creator/profile"), Map.of("displayName", "Meera K", "ratePerReelRupees", 2500), s);
        assertThat(call(post("/api/core/auth/logout"), null, s).getStatus()).isEqualTo(204);

        Session again = login(phone);
        JsonNode p = body(call(get("/api/core/creator/profile"), null, again), 200);
        assertThat(p.get("displayName").asString()).isEqualTo("Meera K");
        assertThat(p.get("ratePerReelRupees").asInt()).isEqualTo(2500);
        assertThat(p.get("completed").asBoolean()).isFalse();
    }

    @Test
    void formChecksAndRequiredFieldsStayOnceComplete() throws Exception {
        Session s = completedManualCreator("rules." + rnd(99999));
        assertThat(problem(call(put("/api/core/creator/profile"), Map.of("sampleLinks",
                List.of("http://evil.example/x")), s), 422)).isEqualTo("invalid-request");
        assertThat(problem(call(put("/api/core/creator/profile"), Map.of("categories", List.of("crypto")), s), 422))
                .isEqualTo("invalid-request");
        assertThat(problem(call(put("/api/core/creator/profile"), Map.of("ratePerReelRupees", 50), s), 422))
                .isEqualTo("invalid-request");
        assertThat(problem(call(put("/api/core/creator/profile"), Map.of("instagramHandle", "bad handle!"), s), 422))
                .isEqualTo("invalid-request");
        // complete: emptying a required field is refused, changing it is fine
        Map<String, Object> noCity = fullForm("rules.x", null, null);
        noCity.remove("city");
        assertThat(problem(call(put("/api/core/creator/profile"), noCity, s), 422)).isEqualTo("invalid-request");
        Map<String, Object> newCity = fullForm("rules.x", null, null);
        newCity.put("city", "Nashik");
        assertThat(body(call(put("/api/core/creator/profile"), newCity, s), 200).get("city").asString())
                .isEqualTo("Nashik");
    }

    @Test
    void onlyCreatorsHaveAPassport() throws Exception {
        MockHttpServletResponse r = mvc.perform(withIp(get("/api/core/creator/profile")).header("X-Dev-Role",
                "BRAND_ADMIN").header("X-Dev-User-Id", UUID.randomUUID().toString()).header("X-Dev-Org-Id",
                UUID.randomUUID().toString())).andReturn().getResponse();
        assertThat(problem(r, 403)).isEqualTo("actor-not-allowed");
        assertThat(call(get("/api/core/creator/profile"), null, null).getStatus()).isEqualTo(401);
    }

    @Test
    void pausingTakesHerOffTheMarketplaceAndResumingPutsHerBack() throws Exception {
        Session s = completedManualCreator("pause." + rnd(99999));
        JsonNode paused = body(call(put("/api/core/creator/listing"), Map.of("listed", false), s), 200);
        assertThat(paused.get("marketplaceStatus").asString()).isEqualTo("HIDDEN");
        assertThat(paused.get("bookable").asBoolean()).isFalse();
        JsonNode back = body(call(put("/api/core/creator/listing"), Map.of("listed", true), s), 200);
        assertThat(back.get("bookable").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------ uploads

    @Test
    void uploadRules() throws Exception {
        Session s = creatorSignup();
        Session other = creatorSignup();
        assertThat(problem(call(post("/api/core/creator/uploads"), Map.of("contentType", "application/pdf"), s), 422))
                .isEqualTo("upload-refused");

        JsonNode ticket = body(call(post("/api/core/creator/uploads"), Map.of("contentType", "image/jpeg"), s), 201);
        assertThat(ticket.get("method").asString()).isEqualTo("PUT");
        assertThat(ticket.get("headers").get("Content-Type").asString()).isEqualTo("image/jpeg");
        assertThat(ticket.get("maxBytes").asLong()).isEqualTo(5L * 1024 * 1024);
        String key = ticket.get("key").asString();
        assertThat(key).startsWith("creator-evidence/" + s.userId() + "/").endsWith(".jpg");

        // not uploaded yet
        assertThat(problem(call(post("/api/core/creator/evidence"), Map.of("uploadKey", key, "kind", "INSIGHTS"), s),
                422)).isEqualTo("upload-refused");
        // the signed URL holds the type: another type is refused by the store
        URI url = URI.create(ticket.get("url").asString());
        assertThat(mvc.perform(put(url).header("Content-Type", "image/png").content(new byte[10])).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
        // a tampered URL is refused
        URI forged = URI.create(url.toString().replace("sig=", "sig=0"));
        assertThat(mvc.perform(put(forged).header("Content-Type", "image/jpeg").content(new byte[10])).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(mvc.perform(put(url).header("Content-Type", "image/jpeg").content(new byte[100])).andReturn().getResponse()
                .getStatus()).isEqualTo(200);

        // someone else's key
        assertThat(problem(call(post("/api/core/creator/evidence"), Map.of("uploadKey", key, "kind", "INSIGHTS"),
                other), 422)).isEqualTo("upload-refused");
        // a key we never issued
        assertThat(problem(call(post("/api/core/creator/evidence"), Map.of("uploadKey", "creator-evidence/"
                + s.userId() + "/x.jpg", "kind", "INSIGHTS"), s), 422)).isEqualTo("upload-refused");
        // works once, never twice
        assertThat(call(post("/api/core/creator/evidence"), Map.of("uploadKey", key, "kind", "INSIGHTS"), s)
                .getStatus()).isEqualTo(201);
        assertThat(problem(call(post("/api/core/creator/evidence"), Map.of("uploadKey", key, "kind", "INSIGHTS"), s),
                422)).isEqualTo("upload-refused");

        // over 5 MB is refused at attach time (a presigned PUT cannot cap the size itself)
        String big = uploadPng(s, 5 * 1024 * 1024 + 1);
        assertThat(problem(call(post("/api/core/creator/evidence"), Map.of("uploadKey", big, "kind", "INSIGHTS"), s),
                422)).isEqualTo("upload-refused");
    }

    @Test
    void theLastScreenshotBehindSelfReportedNumbersCannotBeRemoved() throws Exception {
        Session s = completedManualCreator("keep." + rnd(99999));
        JsonNode p = body(call(get("/api/core/creator/profile"), null, s), 200);
        String firstId = p.get("evidence").get(0).get("id").asString();
        assertThat(problem(call(delete("/api/core/creator/evidence/" + firstId), null, s), 422))
                .isEqualTo("invalid-request");

        String second = uploadPng(s, 1000);
        call(post("/api/core/creator/evidence"), Map.of("uploadKey", second, "kind", "INSIGHTS"), s);
        JsonNode after = body(call(delete("/api/core/creator/evidence/" + firstId), null, s), 200);
        assertThat(after.get("evidence")).hasSize(1);
        assertThat(jdbc.sql("SELECT delete_requested_at IS NOT NULL FROM uploads u JOIN creator_evidence e "
                + "ON e.upload_key = u.key WHERE e.id = :id").param("id", UUID.fromString(firstId))
                .query(Boolean.class).single()).isTrue();
        // and it is not hers to remove twice, nor anyone else's
        assertThat(problem(call(delete("/api/core/creator/evidence/" + firstId), null, s), 404))
                .isEqualTo("evidence-not-found");
    }

    // ------------------------------------------------------------------------------------------------ Instagram

    @Test
    void instagramPathTrustsOnlyBackendAAndCompletesWithVerifiedNumbers() throws Exception {
        Session s = creatorSignup();
        String ref = s.userId().toString();
        Map<String, Object> form = fullForm(null, null, null); // no handle, no numbers, no screenshot
        call(put("/api/core/creator/profile"), form, s);

        JsonNode link = body(call(post("/api/core/creator/instagram/connect"), null, s), 200);
        assertThat(link.get("url").asString()).startsWith("https://www.instagram.com/").contains("state=" + ref);
        assertThat(fakeAi.creatorCalls().getFirst().query()).isEqualTo("account_ref=" + ref);
        assertThat(fakeAi.creatorCalls().getFirst().apiKey()).isEqualTo(FakeAiService.API_KEY);

        // she lands back on "?instagram=connected" but Backend A has nothing: nothing changes
        JsonNode none = body(call(get("/api/core/creator/instagram"), null, s), 200);
        assertThat(none.get("status").asString()).isEqualTo("NOT_CONNECTED");
        assertThat(none.get("profileCompleted").asBoolean()).isFalse();

        fakeAi.connection(ref, "syncing", "mira.skin", true);
        assertThat(body(call(get("/api/core/creator/instagram"), null, s), 200).get("status").asString())
                .isEqualTo("SYNCING");

        fakeAi.connection(ref, "active", "mira.skin", true);
        fakeAi.verifiedProfile("mira.skin", 15200, 5100);
        JsonNode active = body(call(get("/api/core/creator/instagram"), null, s), 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("handle").asString()).isEqualTo("mira.skin");
        assertThat(active.get("profileCompleted").asBoolean()).isTrue();
        // the app polls every 2 s: a poll that changes nothing sends Backend A nothing
        long pushes = creatorOutboxRows(ref);
        assertThat(pushes).isEqualTo(1);
        body(call(get("/api/core/creator/instagram"), null, s), 200);
        body(call(get("/api/core/creator/instagram"), null, s), 200);
        assertThat(creatorOutboxRows(ref)).isEqualTo(pushes);

        JsonNode p = body(call(get("/api/core/creator/profile"), null, s), 200);
        assertThat(p.get("instagram").get("handle").asString()).isEqualTo("mira.skin");
        assertThat(p.get("numbers").get(0).get("source").asString()).isEqualTo("API_VERIFIED");
        assertThat(p.get("numbers").get(0).get("value").asLong()).isEqualTo(15200);
        assertThat(auditCount(s.userId(), "INSTAGRAM_CONNECTED")).isEqualTo(1);
        // a typed handle cannot overwrite the verified one
        Map<String, Object> retype = fullForm("someone.else", null, null);
        assertThat(body(call(put("/api/core/creator/profile"), retype, s), 200).get("instagram").get("handle")
                .asString()).isEqualTo("mira.skin");

        // insights not allowed: told to reconnect
        fakeAi.connection(ref, "active", "mira.skin", false);
        assertThat(body(call(get("/api/core/creator/instagram"), null, s), 200).get("message").asString())
                .contains("reconnect");

        JsonNode off = body(call(delete("/api/core/creator/instagram"), null, s), 200);
        assertThat(off.get("status").asString()).isEqualTo("NOT_CONNECTED");
        assertThat(fakeAi.creatorCalls().stream().anyMatch(c -> c.method().equals("DELETE")
                && c.path().endsWith("/" + ref) && c.query() == null)).isTrue();
    }

    @Test
    void backendADownMeans503ForInstagramButTheManualPathStillWorks() throws Exception {
        Session s = creatorSignup();
        fakeAi.creatorSideDown(true);
        MockHttpServletResponse r = call(post("/api/core/creator/instagram/connect"), null, s);
        assertThat(problem(r, 503)).isEqualTo("dependency-unavailable");
        assertThat(r.getHeader("Retry-After")).isEqualTo("30");
        assertThat(problem(call(get("/api/core/creator/instagram"), null, s), 503)).isEqualTo("dependency-unavailable");
        assertThat(call(put("/api/core/creator/profile"), fullForm("down.ok", 900L, 300L), s).getStatus())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------------ ops

    @Test
    void opsQueueReviewChecksLevelAndBlockAllAudited() throws Exception {
        Session s = completedManualCreator("ops." + rnd(99999));
        UUID me = s.userId();
        String opsId = UUID.randomUUID().toString();

        JsonNode queue = body(ops(get("/api/core/ops/creators/evidence-queue?limit=200"), null, "OPS", opsId), 200);
        JsonNode item = null;
        for (JsonNode q : queue) {
            if (q.get("creatorId").asString().equals(me.toString())) {
                item = q;
            }
        }
        assertThat(item).isNotNull();
        String evidenceId = item.get("evidenceId").asString();

        JsonNode link = body(ops(get("/api/core/ops/creators/evidence/" + evidenceId + "/link"), null, "OPS", opsId),
                200);
        MockHttpServletResponse image = mvc.perform(get(URI.create(link.get("url").asString()))).andReturn()
                .getResponse();
        assertThat(image.getStatus()).isEqualTo(200);
        assertThat(image.getContentType()).isEqualTo("image/png");
        assertThat(auditCount(me, "EVIDENCE_VIEWED")).isEqualTo(1);

        assertThat(problem(ops(put("/api/core/ops/creators/evidence/" + evidenceId), Map.of("accept", false), "OPS",
                opsId), 422)).isEqualTo("invalid-request");
        assertThat(ops(put("/api/core/ops/creators/evidence/" + evidenceId), Map.of("accept", true), "OPS", opsId)
                .getStatus()).isEqualTo(204);
        assertThat(problem(ops(put("/api/core/ops/creators/evidence/" + evidenceId), Map.of("accept", true), "OPS",
                opsId), 422)).isEqualTo("invalid-request");

        JsonNode checked = body(ops(put("/api/core/ops/creators/" + me + "/metrics"), Map.of("metric", "FOLLOWERS",
                "value", 12000, "reason", "Insights screenshot 2026-11-15"), "OPS", opsId), 200);
        assertThat(checked.get("numbers").get(0).get("source").asString()).isEqualTo("OPS_CHECKED");

        assertThat(problem(ops(put("/api/core/ops/creators/" + me + "/level"), Map.of("level", "VERIFIED"), "OPS",
                opsId), 422)).isEqualTo("invalid-request");
        assertThat(body(ops(put("/api/core/ops/creators/" + me + "/level"), Map.of("level", "VERIFIED", "reason",
                "3 collaboration proofs"), "OPS", opsId), 200).get("level").asString()).isEqualTo("VERIFIED");

        JsonNode blocked = body(ops(put("/api/core/ops/creators/" + me + "/block"), Map.of("blocked", true, "reason",
                "Fake screenshots"), "OPS", opsId), 200);
        assertThat(blocked.get("bookable").asBoolean()).isFalse();
        // she cannot lift a block by "resuming"
        assertThat(problem(call(put("/api/core/creator/listing"), Map.of("listed", true), s), 422))
                .isEqualTo("invalid-request");
        assertThat(body(ops(put("/api/core/ops/creators/" + me + "/block"), Map.of("blocked", false, "reason",
                "Re-checked"), "OPS", opsId), 200).get("bookable").asBoolean()).isTrue();

        assertThat(auditCount(me, "EVIDENCE_ACCEPTED")).isEqualTo(1);
        assertThat(auditCount(me, "METRIC_OPS_CHECKED")).isEqualTo(1);
        assertThat(auditCount(me, "CREATOR_LEVEL_CHANGED")).isEqualTo(1);
        assertThat(auditCount(me, "CREATOR_BLOCKED")).isEqualTo(1);

        // brands and creators are not ops
        assertThat(problem(call(get("/api/core/ops/creators/" + me), null, s), 403)).isEqualTo("actor-not-allowed");
        body(ops(get("/api/core/ops/creators/" + me), null, "OPS", opsId), 200);
        assertThat(auditCount(me, "CREATOR_VIEWED")).isEqualTo(1);
    }

    @Test
    void aRejectedScreenshotShowsHerTheReason() throws Exception {
        Session s = completedManualCreator("rej." + rnd(99999));
        String evidenceId = body(call(get("/api/core/creator/profile"), null, s), 200).get("evidence").get(0).get("id")
                .asString();
        ops(put("/api/core/ops/creators/evidence/" + evidenceId), Map.of("accept", false, "note",
                "Screenshot is cropped; show the whole Insights screen"), "OPS", UUID.randomUUID().toString());
        JsonNode p = body(call(get("/api/core/creator/profile"), null, s), 200);
        assertThat(p.get("evidence").get(0).get("status").asString()).isEqualTo("REJECTED");
        assertThat(p.get("evidence").get(0).get("reviewNote").asString()).contains("cropped");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Session completedManualCreator(String handle) throws Exception {
        Session s = creatorSignup();
        call(put("/api/core/creator/profile"), fullForm(handle, 8000L, 2500L), s);
        String key = uploadPng(s, 1500);
        JsonNode done = body(call(post("/api/core/creator/evidence"), Map.of("uploadKey", key, "kind", "INSIGHTS"), s),
                201);
        assertThat(done.get("completed").asBoolean()).isTrue();
        return s;
    }

    static Map<String, Object> fullForm(String handle, Long followers, Long avgViews) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("displayName", "Ananya R");
        f.put("city", "Pune");
        f.put("state", "MH");
        f.put("languages", List.of("Hindi", "Marathi"));
        f.put("categories", List.of("beauty_skincare"));
        f.put("formats", List.of("REEL", "STORY"));
        f.put("ratePerReelRupees", 3000);
        f.put("partnershipAds", true);
        f.put("turnaroundDays", 5);
        f.put("bio", "Skincare for oily skin, in Hindi");
        f.put("sampleLinks", List.of("https://www.instagram.com/reel/ABC123/"));
        if (handle != null) {
            f.put("instagramHandle", "@" + handle);
        }
        if (followers != null) {
            f.put("followers", followers);
        }
        if (avgViews != null) {
            f.put("avgViews", avgViews);
        }
        return f;
    }

    /** Ticket → real PUT to the signed URL (local store) → the key. */
    private String uploadPng(Session s, int bytes) throws Exception {
        JsonNode t = body(call(post("/api/core/creator/uploads"), Map.of("contentType", "image/png"), s), 201);
        byte[] content = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(content);
        assertThat(mvc.perform(put(URI.create(t.get("url").asString())).header("Content-Type", "image/png").content(content))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        return t.get("key").asString();
    }

    private void relayAll() {
        for (int i = 0; i < 20 && relay.publishBatch() > 0; i++) {
            // drain
        }
    }

    private long creatorOutboxRows(String creatorId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'core.creator.v1' AND message_key = :k")
                .param("k", creatorId).query(Long.class).single();
    }

    private long auditCount(UUID target, String action) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE target_user_id = :t AND action = :a").param("t", target)
                .param("a", action).query(Long.class).single();
    }

    private Session creatorSignup() throws Exception {
        return creatorSignup(newPhone());
    }

    private Session creatorSignup(String phone) throws Exception {
        String challenge = sendAndVerify(phone, "SIGNUP_DETAILS_REQUIRED");
        MockHttpServletResponse r = call(post("/api/core/auth/signup"), Map.of("challengeId", challenge,
                "accountType", "CREATOR", "acceptTerms", true, "confirmAdult", true), null);
        return session(r);
    }

    private Session login(String phone) throws Exception {
        JsonNode sent = body(call(post("/api/core/auth/otp/send"), Map.of("channel", "PHONE", "destination", phone),
                null), 202);
        String code = messages.last(phone).orElseThrow().text().substring(0, 6);
        return session(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", sent.get("challengeId")
                .asString(), "code", code), null));
    }

    private String sendAndVerify(String phone, String expectedNext) throws Exception {
        JsonNode sent = body(call(post("/api/core/auth/otp/send"), Map.of("channel", "PHONE", "destination", phone),
                null), 202);
        String id = sent.get("challengeId").asString();
        String code = messages.last(phone).orElseThrow().text().substring(0, 6);
        assertThat(body(call(post("/api/core/auth/otp/verify"), Map.of("challengeId", id, "code", code), null), 200)
                .get("next").asString()).isEqualTo(expectedNext);
        return id;
    }

    private JsonNode me(Session s) throws Exception {
        return body(call(get("/api/core/auth/me"), null, s), 200);
    }

    private MockHttpServletResponse ops(MockHttpServletRequestBuilder b, Object body, String role, String userId)
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

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static String newPhone() {
        return "+9198" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static int rnd(int n) {
        return ThreadLocalRandom.current().nextInt(n);
    }
}
