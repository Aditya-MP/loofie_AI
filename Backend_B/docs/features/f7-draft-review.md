# F7 Draft upload and review: build report

Date: 2026-11-18. Plan: [f7-draft-review-plan.md](f7-draft-review-plan.md) (D1-D9 approved 2026-11-18).
Status: **built and tested**; see section 9 for the load result and section 10 for what is still open.

## 1. What the feature does

The moment money is earned. The creator uploads her video straight to storage; the app checks it (9:16, at least
1080x1920, duration within the brief, has sound) and makes a 720p preview with a "LOOFIE PREVIEW" watermark and the
job reference on every frame. Only then does the brand hear about it: it watches the preview, answers the brief's
checklist yes / no, and either approves (the full-quality file unlocks), asks for the one free revision (for the "no"
items only, each with a note) or rejects (after the free revision; it becomes a dispute, F9). Approval opens a 7-day
rating (1-5 stars) that goes to Backend A.

## 2. Flow

```
creator  POST /creator/jobs/{id}/drafts/upload  -> signed PUT (MP4/MOV, 500 MB, 60 min)
         PUT the file to storage
         POST /creator/jobs/{id}/drafts {key}   -> 202 CHECKING (key must be hers, unused, really uploaded)
worker   download -> ffprobe -> checks
           fail  -> FAILED with plain reasons; creator told; job unchanged; brand hears nothing
           pass  -> PREVIEWING -> ffmpeg 720p + watermark -> preview stored
                 -> one transaction: SUBMIT_DRAFT (or RESUBMIT) as the creator, PREVIEW_READY, draft READY,
                    brand + creator notified. The review clock starts here.
brand    GET  /brand/jobs/{id}/draft             -> preview link (30 min), checklist, review deadline
         POST /brand/jobs/{id}/draft/review      -> APPROVE (admin) | REVISION (admin or member) | REJECT (admin)
         POST /brand/jobs/{id}/draft/master      -> after approval only: 15-minute link, audited
         POST /brand/jobs/{id}/draft/rating      -> 1-5, once, within 7 days of approval
ops      PUT  /ops/jobs/{id}/brief               -> the brief copy (D1), until Backend A sends it
         GET  /ops/jobs/{id}/drafts              -> every video, checks, answers, rating
         POST /ops/jobs/{id}/drafts/{d}/open     -> preview or master link, reason required, audited
```

## 3. Decisions as built

| # | Decision | Built |
|---|---|---|
| D1 | Brief copy on the job | `job_briefs` (checklist 1-12 items, min/max seconds, ad-rights days, source). Ops enters it (`PUT /ops/jobs/{id}/brief`, reason, audited); locked once a video was uploaded. Without one: 5 default checklist items and 15-90 s, shown as "default" to both sides. **Backend A sending it at job creation is part of the F4 handover with Aditya** |
| D2 | FFmpeg inside core-service | `media.MediaTools` + `DraftWorker` (queue in `job_drafts`, lease, SKIP LOCKED, `max-concurrent` per instance). If ffmpeg, ffprobe or the font is missing the worker does not start and says why; videos wait, nothing fails |
| D3 | FFmpeg on this machine | Installed (FFmpeg 9.0.2, winget). Tests run on real files made by ffmpeg |
| D4 | Limits | MP4 / MOV, 500 MB; duration from the brief, else 15-90 s; 0.5 s tolerance; 2 % ratio tolerance |
| D5 | Paid revision | Deferred (needs a payment; checkout is Backend A's) |
| D6 | Review window | 120 calendar hours, unchanged |
| D7 | Rating | 1-5 + optional comment, once, 7 days after approval; stars to Backend A as `rating` (their contract example 21); the comment never leaves core |
| D8 | F6 photos on the register | Receipt, handover and damage files now need an upload link (`POST /api/core/jobs/{id}/uploads`) and must be the acting person's own, unused upload; claimed in the same transaction as the move. Key shape unchanged (`receipts/<uuid>.jpg`), so the shipments table checks still hold |
| D9 | Retention | Approved: ad-rights days (default 90) + 30 days. Failed or never delivered: 7 days. Delivered but never approved: 180 days after upload, once the job ended. Sweep every 30 min; files removed by the upload cleanup |

## 4. Rules worth knowing

- **A video reaches the brand only through the checks.** The engine refuses SUBMIT_DRAFT / RESUBMIT / PREVIEW_READY
  without a draft that passed and has its preview (`DraftGate`); the HTTP endpoint can never supply one.
- **Processing time is nobody's loss.** "On time" is judged by the upload time; the production deadline waits while a
  video uploaded before it is being checked (the timer defers, no alarm); the brand's review clock starts only when
  the preview is ready. A video uploaded after the deadline does not hold the clock.
- **Our trouble never fails her video.** Storage or tool problems are retried (30 s, 2 min, 10 min, then every 30 min);
  ops is told once after the third try (`DRAFT_PROCESSING_STUCK`). Only a file the tools refuse is failed, with a
  plain "export it again" message. A preview that times out three times counts as the file's problem.
- **One video in processing per job** (unique index); at most 20 uploads per job.
- **The master is locked** until APPROVED (or later states); before that the brand gets 404, the same as "no such
  video". Ops can open any video for a dispute, with a reason, audited.
- **The free revision is only for "no" items**, each with a note of at least 5 characters; every checklist item must
  be answered; a newer video makes an older review stale (refused). Approve and reject are admin-only (approval
  releases payment); members may ask for the revision.
- **Account deletion keeps delivered videos** (the brand's licence) until their retention ends; everything else of the
  person is removed as before.

## 5. Security

FFmpeg is run with fixed argument lists (no shell), the MP4/MOV reader forced (`-f mov`) and only local files allowed
(`-protocol_whitelist file`), so a playlist disguised as a video is refused (tested). Time limits (probe 30 s,
transcode 10 min), output size cap (200 MB), no stdin. The watermark text is built from the job id's hex digits only.
Upload keys are the person's own; preview links last 30 minutes, master links 15 minutes and are audited.

## 6. Data (V19)

`job_briefs`, `job_drafts` (also the queue), `draft_reviews` (one per draft), `job_ratings`; upload purposes
`DRAFT_VIDEO`, `DRAFT_PREVIEW`, `RECEIPT_PHOTO`, `HANDOVER_PHOTO`, `DAMAGE_EVIDENCE`.

## 7. Code

| Area | Classes |
|---|---|
| Domain | `draft.domain.Brief`, `VideoChecks`, `DraftStatus`; `job.domain.DraftGate` (port), `TransitionDeferredException` |
| App | `CreatorDraftService`, `BrandDraftService`, `OpsDraftService`, `DraftProcessor`, `DraftWorker`(+`Scheduler`), `DraftRetention`, `DraftGateAdapter`; `job.app.JobFacts`, `JobActions` (D8) |
| Infra | `DraftRepository`, `BriefRepository`, `ReviewRepository`; `storage` download / upload, new purposes |
| Media | `media.MediaTools`, `MediaProperties` (`loofie.media.*`) |
| API | `draft.api.DraftControllers`, `job.api.JobUploadController` |
| Engine | `JobTransitionService.checkDraft` (guards, on-time by upload time, approval hook); `DeadlineWorker` defers quietly |

## 8. Tests

350 tests, 0 failures, 2 skipped (18 new); end-to-end check 64 of 64. `DraftFlowTest` (12, HTTP + Postgres + real ffmpeg): the happy path with the
preview probed (720x1280, sound) and **the watermark checked on the pixels** · MOV and a rotated portrait pass · every
failed check (landscape, 720p, silent, too long, too short, a playlist disguised as MP4) explained, brand told nothing ·
the brief copy sets duration and checklist, is validated and locks · upload rules (type, roles, someone else's key,
not uploaded, same key twice, one at a time, not in review) and the engine refusing a direct SUBMIT_DRAFT · the
checklist revision (all-yes refused, notes, every item, member cannot approve, stale video, one free), approval, the
original master downloaded and audited, retention date, rating once and sent to Backend A without the comment · the
rating window and reject-after-revision with ops told and the video opened by ops · the deadline waiting for a video
uploaded in time and not for one uploaded late · storage trouble retried, never failed, ops told once, then delivered ·
a job that moves on while processing · retention removing files · account deletion keeping delivered videos.
`VideoChecksTest` (4): tolerances and briefs. `JobPhotoUploadTest` (2): D8. `ArchitectureTest`: draft tables only
through the draft package.

## 9. Load (this laptop: Windows 11, 16 cores, PostgreSQL 18 local, local file store)

Tools: `scripts/perf/DraftLoadTest.java`, `scripts/perf/draft-seed.sql`. Method as in earlier rounds: a discarded
warm-up, then measured on fresh jobs.

**Run 1, endpoints (1,000 of each at 100 rps, warm):** 4,000 requests, 0 errors.

| Endpoint | p50 | p95 | p99 | Plan gate 50 ms | Project write gate 300 ms |
|---|---|---|---|---|---|
| Brand view | 5 ms | 9 ms | 15 ms | pass | – |
| Creator view | 4 ms | 8 ms | 13 ms | pass | – |
| Submit | 13 ms | 126 ms | 220 ms | **miss** | pass |
| Review (approve) | 15 ms | 75 ms | 125 ms | **miss** | pass |

Submit's tail is the store check: it must confirm the file is really there (outside any transaction). With the local
dev store that is a file read that Windows Defender delays by about 20 ms for a new file (JFR: 18-24 ms per read); in
production it is an S3 HEAD. Review does about 12 writes in one transaction (state, log, outbox, notice, review,
retention). Both are within the 300 ms gate every other write endpoint uses; the 50 ms in the plan was too tight for
writes and I recommend 300 ms for them.

**Run 2, processing (20 real 60 s 1080x1920 videos, 42.6 MB each, queued at once, 2 at a time):** 20 / 20 READY,
0 failed, 0 stuck, 24.5 s per video wall time (about 2.7 cores per ffmpeg, measured). **Gate passes.**
API while transcoding: p50 10 ms, but p95 913 ms with 98 × 503 and 402 refused connections over 48,000 requests.
**This gate is not met on this laptop**, and the cause is not F7's processing: the same stalls appear with the worker
switched off and nothing transcoding (baseline p95 0.4-1.8 s in four runs), and one later run of the identical
baseline gave p95 4 ms. Machine CPU stayed under half the cores (sampled every 15 s); GC pauses under 35 ms; the stalls
are database round trips that sometimes take 100-600 ms for one-row reads. This matches the unexplained stalls already
recorded in round C on this machine. It must be re-measured on the real server before launch.

**Found and fixed on the way:**
- *Pool collapse on a cold start (all of it measured):* a screen took a connection seven times per request; on a cold
  JVM at 100 rps, 200 requests queued for 20 connections (p50 2 s, 503s and 500s). Now every F7 screen reads in one
  read-only transaction behind a fence (12 at once, 1 s wait, then a quick 503). "No connection" is now 503, not 500.
- *FFmpeg used more cores than set:* `-threads 2` only limited the encoder; the decoder and filters are now capped too
  (2.7 cores per video, from 3.0), and on Linux the transcoder runs under `nice -n 15` so API requests always come first.
- *Ops flooding:* a storage outage would have sent one "processing stuck" notice per video; now one a day with the
  count, the oldest and an example.
- *Local store:* uploads are streamed to disk (were held in memory, 20 MB cap) so real phone videos work in development.

**Still true for every endpoint, not only F7:** right after a start, before the JVM has warmed up, a burst of 100 rps
can still produce 503s for a few seconds (now fast 503s, no 500s, no pile-up). The fix is operational: a warm-up or a
gradual traffic ramp on deploy.

## 10. Open items

| Item | Who |
|---|---|
| Backend A sends the brief copy at job creation (D1) | Aditya (F4 handover) |
| Paid revision (D5) | Waits for Backend A's checkout |
| Production image with ffmpeg, ffprobe and a TrueType font; storage bucket | Hosting decision (same as round C) |
| The creator has no deadline while REVISION_REQUESTED (engine, from F5) | Founder: how long for a fix? |
| Write-endpoint gate: 300 ms (as F5/F6/refunds) instead of the plan's 50 ms | Rishabh |
| API-during-transcoding gate re-measured on the real server (laptop stalls also without transcoding) | Hosting |
| Deploy with a warm-up / gradual ramp (cold JVM bursts give short 503s, all endpoints) | Hosting |
