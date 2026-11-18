package com.loofie.core.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.loofie.core.draft.app.DraftProcessor;
import com.loofie.core.draft.app.DraftRetention;
import com.loofie.core.draft.app.DraftWorker;
import com.loofie.core.job.app.JobTransitionService;
import com.loofie.core.job.app.TransitionCommand;
import com.loofie.core.job.app.TransitionDetails;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.timer.DeadlineWorker;
import com.loofie.core.messaging.OutboxRelay;
import com.loofie.core.storage.ObjectStore;
import com.loofie.core.storage.UploadCleanup;
import com.loofie.core.support.FakeAiService;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.JobFixtures;
import com.loofie.core.support.MutableClock;
import com.loofie.core.support.SampleVideos;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F7 end to end on real video files and the real FFmpeg (D3): upload, checks, watermarked preview, the brand's
 * checklist, the one free revision, approval, the locked master, rating, deadlines, retries and retention.
 */
@IntegrationTest
@AutoConfigureMockMvc
class DraftFlowTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JobFixtures fixtures;
    @Autowired DraftWorker worker;
    @Autowired DraftRetention retention;
    @Autowired UploadCleanup cleanup;
    @Autowired DeadlineWorker timers;
    @Autowired MutableClock clock;
    @Autowired FakeAiService fakeAi;
    @Autowired OutboxRelay relay;
    @Autowired ObjectStore store;
    @Autowired JobTransitionService engine;

    record Job(UUID jobId, UUID brandOrgId, UUID creatorId, UUID adminId, UUID memberId) {
    }

    @BeforeEach
    void freshClock() {
        clock.set(Instant.parse("2026-11-18T06:00:00Z"));
        assertThat(worker.toolsProblem()).as("ffmpeg, ffprobe and the font must be installed (D3)").isNull();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void aGoodVideoIsCheckedPreviewedWatermarkedAndOnlyThenReachesTheBrand() throws Exception {
        Job j = inProduction(Duration.ofHours(10));
        String key = upload(j, SampleVideos.good(), "video/mp4");
        JsonNode submitted = body(as(post("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)),
                Map.of("key", key), 202);
        assertThat(submitted.get("status").asString()).isEqualTo("CHECKING");

        // Before processing the brand sees nothing and the job has not moved.
        JsonNode before = body(as(get("/api/core/brand/jobs/{id}/draft", j.jobId()), admin(j)), null, 200);
        assertThat(before.get("current").isNull()).isTrue();
        assertThat(before.get("previewUrl").isNull()).isTrue();
        assertThat(fixtures.status(j.jobId())).isEqualTo("IN_PRODUCTION");

        assertThat(process(j.jobId())).isEqualTo(DraftProcessor.Outcome.READY);
        assertThat(fixtures.status(j.jobId())).isEqualTo("IN_REVIEW");
        // Logged as the creator's submission, then the system's "preview ready"; on time by the upload time.
        assertThat(jdbc.sql("SELECT action || ':' || actor_type FROM job_events WHERE job_id = ? ORDER BY occurred_at, "
                + "job_version").param(j.jobId()).query(String.class).list())
                .containsSubsequence("SUBMIT_DRAFT:CREATOR", "PREVIEW_READY:SYSTEM");
        assertThat(jdbc.sql("SELECT first_draft_on_time FROM jobs WHERE id = ?").param(j.jobId()).query(Boolean.class)
                .single()).isTrue();
        assertThat(notices(j.brandOrgId(), "DRAFT_READY_FOR_REVIEW")).isEqualTo(1);

        JsonNode view = body(as(get("/api/core/brand/jobs/{id}/draft", j.jobId()), member(j)), null, 200);
        assertThat(view.get("current").get("status").asString()).isEqualTo("READY");
        assertThat(view.get("current").get("probe").get("width").asInt()).isEqualTo(1080);
        assertThat(view.get("current").get("probe").get("height").asInt()).isEqualTo(1920);
        assertThat(view.get("brief").get("fromBrief").asBoolean()).as("no brief copy: defaults").isFalse();
        assertThat(view.get("brief").get("checklist")).hasSize(5);
        assertThat(view.get("masterAvailable").asBoolean()).isFalse();
        assertThat(view.get("reviewDueAt").isNull()).isFalse();

        // The preview: 720 wide, portrait, with sound, smaller than the master, a different file.
        byte[] preview = fetch(view.get("previewUrl").asString());
        assertThat(SampleVideos.describe(preview)).isEqualTo("720x1280 audio");
        assertThat(view.get("previewUrl").asString()).contains("previews/").doesNotContain("drafts/");
        // The watermark is really drawn: the original's middle band is one flat colour, the preview's is not.
        assertThat(SampleVideos.middleBandContrast(SampleVideos.good())).isLessThan(10);
        assertThat(SampleVideos.middleBandContrast(preview)).isGreaterThan(30);

        // The master stays locked before approval: the same answer as a missing video.
        assertThat(status(as(post("/api/core/brand/jobs/{id}/draft/master", j.jobId()), admin(j)), null)).isEqualTo(404);
    }

    @Test
    void movAndARotatedPortraitPassButThePixelsAloneWouldNot() throws Exception {
        Job mov = inProduction(Duration.ofHours(10));
        submit(mov, upload(mov, SampleVideos.goodMov(), "video/quicktime"));
        assertThat(process(mov.jobId())).isEqualTo(DraftProcessor.Outcome.READY);

        Job rotated = inProduction(Duration.ofHours(10));
        submit(rotated, upload(rotated, SampleVideos.rotatedPortrait(), "video/mp4"));
        assertThat(process(rotated.jobId())).isEqualTo(DraftProcessor.Outcome.READY);
        JsonNode probe = body(as(get("/api/core/creator/jobs/{id}/drafts", rotated.jobId()), creator(rotated)), null, 200)
                .get("drafts").get(0).get("probe");
        assertThat(probe.get("width").asInt()).as("display size after rotation").isEqualTo(1080);
        assertThat(probe.get("height").asInt()).isEqualTo(1920);
    }

    // ------------------------------------------------------------------ checks

    @Test
    void everyFailedCheckIsExplainedToTheCreatorAndTheBrandHearsNothing() throws Exception {
        Job j = inProduction(Duration.ofHours(10));
        Map<String, byte[]> cases = new LinkedHashMap<>();
        cases.put("NOT_PORTRAIT", SampleVideos.landscape());
        cases.put("TOO_SMALL", SampleVideos.small720());
        cases.put("NO_AUDIO", SampleVideos.silent());
        cases.put("TOO_LONG", SampleVideos.tooLong());
        cases.put("TOO_SHORT", SampleVideos.tooShort());
        cases.put("UNREADABLE", SampleVideos.playlistTrick());
        for (Map.Entry<String, byte[]> c : cases.entrySet()) {
            submit(j, upload(j, c.getValue(), "video/mp4"));
            assertThat(process(j.jobId())).as(c.getKey()).isEqualTo(DraftProcessor.Outcome.FAILED);
            JsonNode latest = body(as(get("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)), null, 200)
                    .get("drafts").get(0);
            assertThat(latest.get("status").asString()).isEqualTo("FAILED");
            List<String> codes = new ArrayList<>();
            latest.get("failures").forEach(f -> codes.add(f.get("code").asString()));
            assertThat(codes).as(c.getKey()).contains(c.getKey());
            assertThat(latest.get("failures").get(0).get("message").asString()).isNotBlank();
        }
        assertThat(fixtures.status(j.jobId())).isEqualTo("IN_PRODUCTION");
        assertThat(notices(j.brandOrgId(), "DRAFT_READY_FOR_REVIEW")).isZero();
        assertThat(creatorNotices(j.creatorId(), "DRAFT_CHECKS_FAILED")).isEqualTo(cases.size());
        // Landscape is also too small: both are listed, so she fixes both at once.
        assertThat(jdbc.sql("SELECT failures::text FROM job_drafts WHERE job_id = ? AND attempt = 1").param(j.jobId())
                .query(String.class).single()).contains("NOT_PORTRAIT").contains("TOO_SMALL");
        // After the failures she can still deliver.
        submit(j, upload(j, SampleVideos.good(), "video/mp4"));
        assertThat(process(j.jobId())).isEqualTo(DraftProcessor.Outcome.READY);
    }

    @Test
    void theBriefCopySetsDurationAndChecklistAndIsLockedOnceAVideoArrives() throws Exception {
        Job j = inProduction(Duration.ofHours(10));
        Map<String, Object> brief = Map.of("checklist", List.of(Map.of("key", "hook", "label", "Opens with the problem"),
                        Map.of("key", "code", "label", "Says code GLOW10")),
                "minSeconds", 5, "maxSeconds", 10, "adRightsDays", 30, "reason", "From the signed brief PDF");
        assertThat(status(as(put("/api/core/ops/jobs/{id}/brief", j.jobId()), ops()), Map.of("checklist",
                List.of(Map.of("key", "Bad Key", "label", "x")), "minSeconds", 5, "maxSeconds", 10, "reason",
                "testing it"))).isEqualTo(422);
        assertThat(status(as(put("/api/core/ops/jobs/{id}/brief", j.jobId()), ops()), Map.of("checklist",
                List.of(Map.of("key", "a", "label", "x")), "minSeconds", 50, "maxSeconds", 10, "reason",
                "testing it"))).isEqualTo(422);
        assertThat(status(as(put("/api/core/ops/jobs/{id}/brief", j.jobId()), admin(j)), brief)).isEqualTo(403);
        body(as(put("/api/core/ops/jobs/{id}/brief", j.jobId()), ops()), brief, 200);

        JsonNode mine = body(as(get("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)), null, 200);
        assertThat(mine.get("limits").get("maxSeconds").asInt()).isEqualTo(10);
        assertThat(mine.get("brief").get("checklist")).hasSize(2);

        submit(j, upload(j, SampleVideos.good(), "video/mp4")); // 20 s
        assertThat(process(j.jobId())).isEqualTo(DraftProcessor.Outcome.FAILED);
        assertThat(jdbc.sql("SELECT failures::text FROM job_drafts WHERE job_id = ?").param(j.jobId())
                .query(String.class).single()).contains("TOO_LONG").contains("at most 10 s");
        assertThat(status(as(put("/api/core/ops/jobs/{id}/brief", j.jobId()), ops()), brief)).as("locked").isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'BRIEF_SET_BY_OPS' AND details->>'jobId' = ?")
                .param(j.jobId().toString()).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ who may do what

    @Test
    void uploadsAreTheCreatorsOwnOnePerJobAtATimeAndOnlyWhileProducing() throws Exception {
        Job j = inProduction(Duration.ofHours(10));
        Job other = inProduction(Duration.ofHours(10));
        assertThat(status(as(post("/api/core/creator/jobs/{id}/drafts/upload", j.jobId()), creator(j)),
                Map.of("contentType", "video/x-msvideo"))).as("AVI").isEqualTo(422);
        assertThat(status(as(post("/api/core/creator/jobs/{id}/drafts/upload", j.jobId()), admin(j)),
                Map.of("contentType", "video/mp4"))).as("brand").isEqualTo(403);
        assertThat(status(as(post("/api/core/creator/jobs/{id}/drafts/upload", j.jobId()), creator(other)),
                Map.of("contentType", "video/mp4"))).as("not her job").isEqualTo(404);

        String othersKey = upload(other, SampleVideos.good(), "video/mp4");
        assertThat(problem(as(post("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)),
                Map.of("key", othersKey), 422)).as("someone else's upload").isEqualTo("upload-refused");
        JsonNode ticket = body(as(post("/api/core/creator/jobs/{id}/drafts/upload", j.jobId()), creator(j)),
                Map.of("contentType", "video/mp4"), 200);
        assertThat(ticket.get("ticket").get("maxBytes").asLong()).isEqualTo(500L * 1024 * 1024);
        assertThat(problem(as(post("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)),
                Map.of("key", ticket.get("ticket").get("key").asString()), 422)).as("not uploaded").isEqualTo("upload-refused");

        String key = upload(j, SampleVideos.good(), "video/mp4");
        submit(j, key);
        assertThat(problem(as(post("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)),
                Map.of("key", key), 422)).as("same key twice").isEqualTo("upload-refused");
        String second = upload(j, SampleVideos.good(), "video/mp4");
        assertThat(problem(as(post("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)),
                Map.of("key", second), 422)).as("one in processing at a time").isEqualTo("rule-violated");
        process(j.jobId());
        assertThat(problem(as(post("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)),
                Map.of("key", second), 422)).as("in review now").isEqualTo("rule-violated");

        // The engine refuses a draft that did not come through the checks.
        Job direct = inProduction(Duration.ofHours(10));
        MockHttpServletResponse r = send(as(post("/api/core/jobs/{id}/transitions", direct.jobId()), creator(direct)),
                Map.of("action", "SUBMIT_DRAFT"));
        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(r.getContentAsString()).contains("Upload the video in the app");
    }

    // ------------------------------------------------------------------ the brand's review

    @Test
    void checklistRevisionIsForTheNoItemsOnlyOnceThenApprovalUnlocksTheMasterAndRating() throws Exception {
        Job j = deliveredJob();
        UUID first = currentDraft(j);
        List<Map<String, Object>> allYes = answers(Map.of());
        assertThat(problem(review(j, member(j), first, "REVISION", allYes, null), 422)).isEqualTo("rule-violated");
        assertThat(problem(review(j, member(j), first, "REVISION", answers(Map.of("cta", "")), null), 422))
                .as("a no needs a note").isEqualTo("rule-violated");
        List<Map<String, Object>> missing = new ArrayList<>(allYes);
        missing.removeFirst();
        assertThat(problem(review(j, admin(j), first, "APPROVE", missing, null), 422)).as("every item").isEqualTo("rule-violated");
        assertThat(problem(review(j, member(j), first, "APPROVE", allYes, null), 403)).isEqualTo("actor-not-allowed");

        body(review(j, member(j), first, "REVISION", answers(Map.of("cta", "Say the code GLOW10 at the end")), null), 200);
        assertThat(fixtures.status(j.jobId())).isEqualTo("REVISION_REQUESTED");
        JsonNode mine = body(as(get("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)), null, 200);
        assertThat(mine.get("canUpload").asBoolean()).isTrue();
        JsonNode asked = mine.get("revisionRequested");
        assertThat(asked.get("decision").asString()).isEqualTo("REVISION");
        assertThat(asked.get("answers").toString()).contains("GLOW10");
        assertThat(creatorNotices(j.creatorId(), "DRAFT_REVISION_REQUESTED")).isEqualTo(1);

        submit(j, upload(j, SampleVideos.good(), "video/mp4"));
        assertThat(process(j.jobId())).isEqualTo(DraftProcessor.Outcome.READY);
        assertThat(fixtures.status(j.jobId())).isEqualTo("IN_REVIEW");
        assertThat(jdbc.sql("SELECT action FROM job_events WHERE job_id = ? AND action = 'RESUBMIT'").param(j.jobId())
                .query(String.class).list()).hasSize(1);
        UUID second = currentDraft(j);
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.sql("SELECT status FROM job_drafts WHERE id = ?").param(first).query(String.class).single())
                .isEqualTo("SUPERSEDED");

        assertThat(problem(review(j, admin(j), first, "APPROVE", allYes, null), 422)).as("old video").isEqualTo("rule-violated");
        assertThat(problem(review(j, admin(j), second, "REVISION", answers(Map.of("hook", "Faster hook")), null), 422))
                .as("one free revision").isEqualTo("rule-violated");
        body(review(j, admin(j), second, "APPROVE", allYes, null), 200);
        assertThat(fixtures.status(j.jobId())).isEqualTo("APPROVED");
        assertThat(problem(review(j, admin(j), second, "APPROVE", allYes, null), 422)).isEqualTo("rule-violated");

        // The master: a short link, audited, and it is the original file.
        JsonNode link = body(as(post("/api/core/brand/jobs/{id}/draft/master", j.jobId()), member(j)), null, 200);
        assertThat(link.get("url").asString()).contains("drafts/");
        assertThat(fetch(link.get("url").asString())).isEqualTo(SampleVideos.good());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'DRAFT_MASTER_DOWNLOADED' "
                + "AND details->>'jobId' = ?").param(j.jobId().toString()).query(Long.class).single()).isEqualTo(1);

        // D9: kept for the default 90 days of ad rights plus 30.
        OffsetDateTime deleteAfter = jdbc.sql("SELECT delete_after FROM job_drafts WHERE id = ?").param(second)
                .query(OffsetDateTime.class).single();
        assertThat(deleteAfter.toInstant()).isEqualTo(clock.instant().plus(Duration.ofDays(120)));

        // D7: rating, once, 1-5, to Backend A without the comment.
        assertThat(problem(as(post("/api/core/brand/jobs/{id}/draft/rating", j.jobId()), member(j)),
                Map.of("stars", 6), 422)).isEqualTo("rule-violated");
        JsonNode rated = body(as(post("/api/core/brand/jobs/{id}/draft/rating", j.jobId()), member(j)),
                Map.of("stars", 5, "comment", "Lovely energy, will rebook"), 200);
        assertThat(rated.get("rating").asInt()).isEqualTo(5);
        assertThat(problem(as(post("/api/core/brand/jobs/{id}/draft/rating", j.jobId()), admin(j)),
                Map.of("stars", 4), 422)).as("once").isEqualTo("rule-violated");
        for (int i = 0; i < 20 && relay.publishBatch() > 0; i++) {
            // drain
        }
        FakeAiService.Received sent = fakeAi.forJob(j.jobId().toString()).stream()
                .filter(r -> r.body().get("event_type").asString().equals("rating")).findFirst().orElseThrow();
        assertThat(sent.body().get("actor_type").asString()).isEqualTo("brand");
        assertThat(sent.payload().get("stars").asInt()).isEqualTo(5);
        assertThat(sent.payload().get("creator_ref").asString()).isEqualTo(j.creatorId().toString());
        assertThat(sent.body().toString()).doesNotContain("Lovely");
    }

    @Test
    void ratingClosesSevenDaysAfterApprovalAndRejectNeedsAReasonAfterTheFreeRevision() throws Exception {
        Job j = deliveredJob();
        body(review(j, admin(j), currentDraft(j), "APPROVE", answers(Map.of()), null), 200);
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        assertThat(problem(as(post("/api/core/brand/jobs/{id}/draft/rating", j.jobId()), admin(j)),
                Map.of("stars", 3), 422)).isEqualTo("rule-violated");

        Job r = deliveredJob();
        assertThat(problem(review(r, admin(r), currentDraft(r), "REJECT", answers(Map.of()), "bad video"), 422))
                .as("reject only after the free revision").isEqualTo("rule-violated");
        body(review(r, admin(r), currentDraft(r), "REVISION", answers(Map.of("hook", "Hook is slow")), null), 200);
        submit(r, upload(r, SampleVideos.good(), "video/mp4"));
        process(r.jobId());
        assertThat(problem(review(r, admin(r), currentDraft(r), "REJECT", answers(Map.of()), "bad"), 422))
                .as("a reason").isEqualTo("rule-violated");
        body(review(r, admin(r), currentDraft(r), "REJECT", answers(Map.of()), "Product never shown on camera"), 200);
        assertThat(fixtures.status(r.jobId())).isEqualTo("DISPUTED");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_type = 'OPS' AND kind = 'DRAFT_REJECTED' "
                + "AND job_id = ?").param(r.jobId()).query(Long.class).single()).isEqualTo(1);

        // Ops opens the video for the dispute: reason required, audited; the brand still has no master.
        UUID draft = currentDraft(r);
        assertThat(status(as(post("/api/core/ops/jobs/{id}/drafts/{d}/open?master=true", r.jobId(), draft), ops()),
                Map.of("reason", ""))).isEqualTo(422);
        body(as(post("/api/core/ops/jobs/{id}/drafts/{d}/open?master=true", r.jobId(), draft), ops()),
                Map.of("reason", "Dispute: brand says product not shown"), 200);
        assertThat(status(as(post("/api/core/brand/jobs/{id}/draft/master", r.jobId()), admin(r)), null)).isEqualTo(404);
        JsonNode opsView = body(as(get("/api/core/ops/jobs/{id}/drafts", r.jobId()), ops()), null, 200);
        assertThat(opsView.get("drafts")).hasSize(2);
        assertThat(opsView.get("reviews")).hasSize(2);
    }

    // ------------------------------------------------------------------ clocks, failures, retention

    @Test
    void theProductionDeadlineWaitsForAVideoUploadedInTime() throws Exception {
        Job j = inProduction(Duration.ofMinutes(30));
        submit(j, upload(j, SampleVideos.good(), "video/mp4"));
        clock.advance(Duration.ofHours(1)); // deadline passed while the video waits in the queue
        timers.runOnce();
        assertThat(fixtures.status(j.jobId())).as("not expired").isEqualTo("IN_PRODUCTION");
        assertThat(process(j.jobId())).isEqualTo(DraftProcessor.Outcome.READY);
        assertThat(fixtures.status(j.jobId())).isEqualTo("IN_REVIEW");
        assertThat(jdbc.sql("SELECT first_draft_on_time FROM jobs WHERE id = ?").param(j.jobId()).query(Boolean.class)
                .single()).as("judged by upload time").isTrue();

        // A video uploaded after the deadline does not hold the clock.
        Job late = inProduction(Duration.ofMinutes(30));
        clock.advance(Duration.ofMinutes(31));
        submit(late, upload(late, SampleVideos.good(), "video/mp4"));
        timers.runOnce();
        assertThat(fixtures.status(late.jobId())).isEqualTo("REPLACEMENT_NEEDED");
        assertThat(process(late.jobId())).isEqualTo(DraftProcessor.Outcome.SUPERSEDED);
    }

    @Test
    void ourOwnTroubleIsRetriedNeverFailsTheVideoAndTellsOpsOnce() throws Exception {
        Job j = inProduction(Duration.ofHours(10));
        String key = upload(j, SampleVideos.good(), "video/mp4");
        submit(j, key);
        store.delete(key); // the store loses the file: our problem, not hers
        for (int i = 0; i < 4; i++) {
            assertThat(processOnce(j.jobId())).isEqualTo(DraftProcessor.Outcome.RETRY);
            clock.advance(Duration.ofMinutes(31));
        }
        assertThat(jdbc.sql("SELECT status FROM job_drafts WHERE job_id = ?").param(j.jobId()).query(String.class)
                .single()).isEqualTo("CHECKING");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_type = 'OPS' AND kind = "
                + "'DRAFT_PROCESSING_STUCK' AND payload->>'jobId' = ?").param(j.jobId().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(creatorNotices(j.creatorId(), "DRAFT_CHECKS_FAILED")).isZero();
        // The file comes back: the next try delivers.
        store.upload(key, sample(SampleVideos.good()), "video/mp4");
        assertThat(process(j.jobId())).isEqualTo(DraftProcessor.Outcome.READY);
    }

    @Test
    void aJobThatMovesOnWhileTheVideoIsCheckedKeepsItButDoesNotDeliverIt() throws Exception {
        Job j = inProduction(Duration.ofHours(10));
        submit(j, upload(j, SampleVideos.good(), "video/mp4"));
        engine.transition(TransitionCommand.of(j.jobId(), JobAction.REPORT_DELIVERY_ISSUE, Actor.ops(UUID.randomUUID()),
                "Creator called: product leaking").with(TransitionDetails.issue(
                com.loofie.core.job.domain.DeliveryIssueType.DAMAGED)));
        assertThat(process(j.jobId())).isEqualTo(DraftProcessor.Outcome.SUPERSEDED);
        assertThat(fixtures.status(j.jobId())).isEqualTo("DELIVERY_ISSUE");
        assertThat(notices(j.brandOrgId(), "DRAFT_READY_FOR_REVIEW")).isZero();
    }

    @Test
    void videosAreRemovedWhenTheirRetentionEnds() throws Exception {
        Job failed = inProduction(Duration.ofHours(10));
        String badKey = upload(failed, SampleVideos.landscape(), "video/mp4");
        submit(failed, badKey);
        process(failed.jobId());

        Job approved = deliveredJob();
        body(as(put("/api/core/ops/jobs/{id}/brief", approved.jobId()), ops()), Map.of("checklist",
                List.of(Map.of("key", "a", "label", "x")), "minSeconds", 5, "maxSeconds", 60, "adRightsDays", 30,
                "reason", "too late, a video exists"), 422);
        body(review(approved, admin(approved), currentDraft(approved), "APPROVE", answers(Map.of()), null), 200);
        String master = jdbc.sql("SELECT master_key FROM job_drafts WHERE job_id = ?").param(approved.jobId())
                .query(String.class).single();
        String preview = jdbc.sql("SELECT preview_key FROM job_drafts WHERE job_id = ?").param(approved.jobId())
                .query(String.class).single();

        clock.advance(Duration.ofDays(8));
        retention.runOnce();
        cleanup.runOnce();
        assertThat(store.stat(badKey)).as("failed: 7 days").isEmpty();
        assertThat(store.stat(master)).as("approved: still in its ad-rights period").isPresent();

        clock.advance(Duration.ofDays(120));
        retention.runOnce();
        cleanup.runOnce();
        assertThat(store.stat(master)).isEmpty();
        assertThat(store.stat(preview)).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM job_drafts WHERE job_id = ?").param(approved.jobId())
                .query(String.class).single()).isEqualTo("PURGED");
    }

    @Test
    void accountDeletionKeepsDeliveredVideosForTheBrandsLicence() {
        Job j = inProduction(Duration.ofHours(10));
        jdbc.sql("""
                INSERT INTO uploads (key, owner_id, purpose, content_type, max_bytes, issued_at, expires_at, used_at)
                VALUES ('drafts/x/1.mp4', ?, 'DRAFT_VIDEO', 'video/mp4', 1, now(), now(), now()),
                       ('drafts/x/2.mp4', ?, 'DRAFT_VIDEO', 'video/mp4', 1, now(), now(), NULL),
                       ('creator-evidence/x/3.jpg', ?, 'CREATOR_EVIDENCE', 'image/jpeg', 1, now(), now(), now())""")
                .params(j.creatorId(), j.creatorId(), j.creatorId()).update();
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(s ->
                uploadsRegister.requestDeleteAll(j.creatorId()));
        assertThat(jdbc.sql("SELECT key FROM uploads WHERE owner_id = ? AND delete_requested_at IS NOT NULL ORDER BY key")
                .param(j.creatorId()).query(String.class).list())
                .containsExactly("creator-evidence/x/3.jpg", "drafts/x/2.mp4");
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired com.loofie.core.storage.Uploads uploadsRegister;

    // ------------------------------------------------------------------ helpers

    private Job inProduction(Duration left) {
        JobFixtures.Seeded s = fixtures.jobIn(JobStatus.IN_PRODUCTION);
        fixtures.setDeadline(s.jobId(), "production_due_at", clock.instant().plus(left));
        return new Job(s.jobId(), s.brandOrgId(), s.creatorId(), UUID.randomUUID(), UUID.randomUUID());
    }

    /** A job whose first video passed and is in review. */
    private Job deliveredJob() throws Exception {
        Job j = inProduction(Duration.ofHours(10));
        submit(j, upload(j, SampleVideos.good(), "video/mp4"));
        assertThat(process(j.jobId())).isEqualTo(DraftProcessor.Outcome.READY);
        return j;
    }

    private String upload(Job j, byte[] video, String type) throws Exception {
        JsonNode t = body(as(post("/api/core/creator/jobs/{id}/drafts/upload", j.jobId()), creator(j)),
                Map.of("contentType", type), 200).get("ticket");
        int put = mvc.perform(put(URI.create(t.get("url").asString())).header("Content-Type", type).content(video))
                .andReturn().getResponse().getStatus();
        assertThat(put).isEqualTo(200);
        return t.get("key").asString();
    }

    private void submit(Job j, String key) throws Exception {
        body(as(post("/api/core/creator/jobs/{id}/drafts", j.jobId()), creator(j)), Map.of("key", key), 202);
    }

    /** Runs the worker until this job's video is out of processing; other tests' leftovers may come first. */
    private DraftProcessor.Outcome process(UUID jobId) {
        for (int i = 0; i < 30; i++) {
            DraftProcessor.Outcome o = processOnce(jobId);
            if (o != null && o != DraftProcessor.Outcome.RETRY) {
                return o;
            }
        }
        throw new AssertionError("video of job " + jobId + " still in processing");
    }

    /** One worker pass that reached this job's newest video (the worker may take other tests' leftovers first). */
    private DraftProcessor.Outcome processOnce(UUID jobId) {
        for (int i = 0; i < 30; i++) {
            int before = latestTries(jobId);
            Map<DraftProcessor.Outcome, Integer> r = worker.runOnce();
            if (latestTries(jobId) > before) {
                String status = jdbc.sql("SELECT status FROM job_drafts WHERE job_id = ? ORDER BY attempt DESC LIMIT 1")
                        .param(jobId).query(String.class).single();
                return switch (status) {
                    case "READY" -> DraftProcessor.Outcome.READY;
                    case "FAILED" -> DraftProcessor.Outcome.FAILED;
                    case "SUPERSEDED" -> DraftProcessor.Outcome.SUPERSEDED;
                    default -> DraftProcessor.Outcome.RETRY;
                };
            }
            if (r.isEmpty()) {
                return null;
            }
        }
        return null;
    }

    private int latestTries(UUID jobId) {
        return jdbc.sql("SELECT tries FROM job_drafts WHERE job_id = ? ORDER BY attempt DESC LIMIT 1").param(jobId)
                .query(Integer.class).optional().orElse(0);
    }

    private UUID currentDraft(Job j) {
        return jdbc.sql("SELECT id FROM job_drafts WHERE job_id = ? AND status = 'READY' ORDER BY attempt DESC LIMIT 1")
                .param(j.jobId()).query(UUID.class).single();
    }

    private List<Map<String, Object>> answers(Map<String, String> no) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : List.of("hook", "product", "key_points", "cta", "disclosure")) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("key", key);
            a.put("ok", !no.containsKey(key));
            if (no.containsKey(key)) {
                a.put("note", no.get(key));
            }
            out.add(a);
        }
        return out;
    }

    private MockHttpServletRequestBuilder review(Job j, String[] who, UUID draftId, String decision,
                                                 List<Map<String, Object>> answers, String note) {
        return withBody(as(post("/api/core/brand/jobs/{id}/draft/review", j.jobId()), who), reviewBody(draftId,
                decision, answers, note));
    }

    private Map<String, Object> reviewBody(UUID draftId, String decision, List<Map<String, Object>> answers, String note) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("draftId", draftId.toString());
        b.put("decision", decision);
        b.put("answers", answers);
        if (note != null) {
            b.put("note", note);
        }
        return b;
    }

    private long notices(UUID brandOrgId, String kind) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_type = 'BRAND_ORG' AND recipient_id = ? AND kind = ?")
                .params(brandOrgId, kind).query(Long.class).single();
    }

    private long creatorNotices(UUID creatorId, String kind) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_type = 'CREATOR' AND recipient_id = ? AND kind = ?")
                .params(creatorId, kind).query(Long.class).single();
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
    }

    private java.nio.file.Path sample(byte[] bytes) throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("sample", ".mp4");
        java.nio.file.Files.write(f, bytes);
        return f;
    }

    private byte[] fetch(String url) throws Exception {
        MockHttpServletResponse r = mvc.perform(get(URI.create(url))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return r.getContentAsByteArray();
    }

    private String[] creator(Job j) {
        return new String[] {"CREATOR", j.creatorId().toString(), null};
    }

    private String[] admin(Job j) {
        return new String[] {"BRAND_ADMIN", j.adminId().toString(), j.brandOrgId().toString()};
    }

    private String[] member(Job j) {
        return new String[] {"BRAND_MEMBER", j.memberId().toString(), j.brandOrgId().toString()};
    }

    private String[] ops() {
        return new String[] {"OPS", UUID.randomUUID().toString(), null};
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String[] who) {
        b.header("X-Dev-Role", who[0]).header("X-Dev-User-Id", who[1]);
        if (who[2] != null) {
            b.header("X-Dev-Org-Id", who[2]);
        }
        return b;
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, Object body) {
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return b;
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder b, Object body) throws Exception {
        return mvc.perform(withBody(b, body)).andReturn().getResponse();
    }

    private int status(MockHttpServletRequestBuilder b, Object body) throws Exception {
        return send(b, body).getStatus();
    }

    private JsonNode body(MockHttpServletRequestBuilder b, Object body, int expected) throws Exception {
        MockHttpServletResponse r = send(b, body);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(expected);
        return json.readTree(r.getContentAsString());
    }

    private JsonNode body(MockHttpServletRequestBuilder b, int expected) throws Exception {
        return body(b, null, expected);
    }

    private String problem(MockHttpServletRequestBuilder b, Object body, int expected) throws Exception {
        JsonNode p = body(b, body, expected);
        String type = p.get("type").asString();
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private String problem(MockHttpServletRequestBuilder b, int expected) throws Exception {
        return problem(b, null, expected);
    }
}
