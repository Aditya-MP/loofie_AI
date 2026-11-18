# F7 Draft upload and review: plan

Date: 2026-11-18. Status: **approved 2026-11-18 (D1-D9) and built**: see [f7-draft-review.md](f7-draft-review.md). Scope note: F2 (briefs), F3 (shortlists)
and F4 (checkout) are Backend A's (corrected 2026-11-18); F7 is Backend B's.

## 1. Business purpose

The moment money is earned: the creator delivers the video, the brand checks it against what it paid for, and either
approves (the creator gets paid, F8), asks for one free fix, or disputes (F9). Done badly this is where trust breaks:
a brand downloads the master and walks away without approving, a creator ships a 4:3 video with no audio, or a brand
asks for "a totally different idea" for free. F7 makes the delivery checkable and fair.

## 2. What exists

| Piece | State |
|---|---|
| Engine: `SUBMIT_DRAFT`, `PREVIEW_READY`, `APPROVE` (BRAND_ADMIN only), `AUTO_APPROVE`, `REQUEST_REVISION` (admin or member, one free), `RESUBMIT`, `REJECT` → dispute; review clock (120 calendar h, 5-working-day switch built and off), reminders | **Built (F5)** |
| Storage: signed uploads, upload register, cleanup (round C) | **Built**, reusable; no bucket in production yet |
| F6 photos (receipt, handover, damage) | Accept a key by format only; not on the upload register |
| Video upload, technical checks, 720p watermarked preview, master lock, brand checklist, paid revision, rating | **Not built** |
| FFmpeg | **Not installed** on this machine; no Dockerfile / deployment image yet |

## 3. Proposed flow

```
creator: upload ticket (mp4/mov, ≤ 500 MB) ─▶ PUT to storage ─▶ "submit draft" (key)
   ─▶ media worker: ffprobe checks (9:16, ≥1080×1920, duration within the brief's range, audio track)
        fail ─▶ creator sees exactly what failed; nothing reaches the brand; job stays IN_PRODUCTION
        pass ─▶ SUBMIT_DRAFT ─▶ ffmpeg: 720p preview with watermark ─▶ PREVIEW_READY (IN_REVIEW, clock starts)
brand: watches the preview (short-lived link), ticks the checklist (hook, each must-say, CTA/code, disclosure)
   all yes ─▶ APPROVE (admin) ─▶ master unlocks: signed download link for the brand
   some no ─▶ free revision for the "no" items only (one per job) ─▶ creator re-uploads ─▶ same checks
   outside the brief ─▶ paid revision (25 %): see D5
   silent ─▶ AUTO_APPROVE at the deadline
after approval: brand rates 1-5 (open 7 days, never blocks closing) ─▶ Backend A rating event
```

## 4. Architecture

- `media` package: `MediaProbe` / `MediaTranscoder` ports, an FFmpeg implementation that runs the `ffprobe` /
  `ffmpeg` binaries (paths from configuration) on a file fetched by signed URL, and a **media job queue table** worked
  by one scheduled worker per instance (lease, at most 1-2 videos at once: transcoding is CPU heavy). Never inside a
  request.
- Draft records per job / attempt: master key, preview key, probe results, check results, checklist answers.
- Master lock: the master key is never returned to the brand before APPROVED; after, a 15-minute signed download link
  (audited). The creator always sees her own upload; the brand sees only the preview before approval.
- Uses the round C upload register (owner, purpose `DRAFT_VIDEO`, type, size limit checked on submit).

## 5. Security and abuse

Only issued keys; the video is probed before anything else (a renamed exe fails ffprobe); FFmpeg runs with fixed
arguments only (no user text reaches a command line), a time limit and a size limit; preview watermark carries the job
id (a leaked preview is traceable); master download audited; signed links expire in minutes.

## 6-7. Performance, scalability, reliability

Upload bytes never pass through the app. Transcoding a 60 s 1080×1920 video to 720p takes roughly 10-40 s of CPU; the
queue makes it independent of request load; a crashed worker's job is retried (lease timeout); the review clock starts
only at `PREVIEW_READY`, so processing delays never eat the brand's time. Gate (proposed): 20 drafts queued at once are
all previewed without errors, API p95 unaffected while transcoding; submit / review endpoints p95 ≤ 50 ms at 100 rps.

## 8. Database (V19, proposed)

`job_drafts` (job, attempt, master key, preview key, probe JSON, check results, status UPLOADED / CHECKING / FAILED /
PREVIEWING / READY), `media_jobs` (queue), `draft_reviews` (checklist answers per item, decision, by, at),
`job_ratings` (stars, comment, by, at). Purposes `DRAFT_VIDEO`, `DRAFT_PREVIEW` on `uploads`.

## 9. Failure scenarios

Wrong shape / no audio / too short → creator told, brand never sees it · worker crash mid-transcode → retried · storage
down → upload 503, nothing lost · brand opens preview after expiry → new link · two reviewers at once → one wins
(engine's optimistic lock) · master requested before approval → 404.

## 10. Tests (to add)

Probe rules on real sample files (portrait 1080×1920 with audio passes; landscape, 720p, silent, too long fail);
preview is 720p and watermarked; queue retry; master locked until APPROVED; checklist → revision only for "no" items,
one free; rating window; full flow end to end; load gate above.

## 11. Decisions needed

| # | Decision | Recommended |
|---|---|---|
| **D1** | Where the checklist and duration range come from (the brief is Backend A's) | Backend A hands a **brief snapshot** to Backend B when the job is created (hook, must-say points, CTA / code, disclosure required, min / max seconds), stored on the job; part of the F4 handover to agree with Aditya. Until then, ops can enter it |
| **D2** | Where FFmpeg runs | Inside core-service as a queued background worker (1-2 at a time); the production image includes FFmpeg. Move to a separate media service only if volume needs it |
| **D3** | FFmpeg on this machine for development and tests | Install it (`winget install Gyan.FFmpeg`, about 100 MB) so the checks and preview are tested on real video, not mocked |
| **D4** | Limits | MP4 or MOV, at most 500 MB; duration from the brief (default 15-90 s if absent) |
| **D5** | Paid revision (25 %) | **Defer**: it needs a payment and checkout is Backend A's. Build the free revision now; the paid one when the payment handover exists |
| **D6** | Review window | Keep 120 calendar hours (built) until the founder signs off the 5 working days |
| **D7** | Rating in F7 | Yes: 1-5 stars + optional comment, 7 days, sent to Backend A (`rating` event already in their contract) |
| **D8** | Move F6 photos onto the upload register | Yes, small, same rule everywhere |
| **D9** | Keep videos how long | Master and preview until the ad-rights period ends + 30 days, then deleted by the cleanup (ad-rights days come with the brief snapshot) |
