# F1 round C: creator onboarding, Instagram connect, manual profile, account deletion

Date: 2026-11-18. Status: **done: plan approved 2026-11-18 (C1-C8 as recommended); part 1 (passport, Instagram, manual path, uploads, ops queue) and part 2 (account deletion, storage cleanup) built, tested and load-tested. Not committed.**
Rounds: A identity core (`888ff98`), B brand organisations (`75debd0`), **C creator onboarding (this doc)**.
Sources: Foundation v2 (feature 1, creator passport; feature 3 filters; levels), the agreed F1 decisions
(2026-11-17, flow page), Backend A `docs/API.md` and `DECISIONS.md` section 10 on `origin/backend-a` (`a61e5a7`), and
the code as it stands.

## 1. Business purpose

A creator who signs up today gets a phone login and nothing else: no profile, so no brief can be matched to her and
no brand can see her. Round C makes a creator **bookable**:

- **Creator passport** (Foundation v2 feature 1): handle, city, languages, category, followers, average views of the
  last 10 reels, formats, rate per reel, Partnership-ads willingness, turnaround days, sample links, bio. Every number
  carries where it came from (`API_VERIFIED` / `SELF_REPORTED` / `OPS_CHECKED`) and when. Brands are always shown which
  numbers are verified.
- **Two ways in:** connect Instagram (Professional accounts; numbers verified by Backend A) or fill in a manual profile
  with Insights screenshots (Personal accounts, which many nano-creators have; numbers self-reported until ops checks).
- **Account deletion** (DPDP Act): any user can delete their account; personal data goes at once, Instagram data is
  deleted through Backend A, business records stay as the law requires.

What it unblocks: F3 shortlists (filters by category, city, language, rate, format, level all come from the passport),
the ops verification queue (feature 10), and a lawful privacy promise before launch.

## 2. What exists

| Piece | State |
|---|---|
| Creator phone signup and login, consents, 18+ tick, sessions | Done (round A) |
| `users.onboarding_completed` | Column exists; always false for creators today |
| `users.status` ACTIVE / SUSPENDED / DELETED | Exists; nothing sets DELETED yet |
| `CreatorWork.activeJobIds(creatorId)` port (job package answers) | Exists; used for suspensions; reused for deletion |
| Backend A HTTP client | Only the `/ai/feedback` relay (outbox, `X-API-Key`, retries). No Instagram or profile calls |
| File upload / storage | **None.** F6 accepts photo *keys* (`handovers/<id>.jpg`) with a format check; nothing issues upload URLs |
| Creator profile, levels, marketplace status | **None** in Backend B |

Backend A side (read from `origin/backend-a`): `GET /ai/instagram/login-url?account_ref=`, the Meta callback, `GET
/ai/instagram/accounts/{ref}` (status `syncing` / `active` / `error` / `expired` / `revoked`), `DELETE
/ai/instagram/accounts/{ref}?delete_data=true`, `POST /ai/creators/profile` (only `source: manual_profile`), `GET
/ai/creators/{handle}` (by handle only). Categories in Backend A today: `beauty_skincare`, `personal_care`, `other`.

## 3. Architecture and dependencies

Ownership (product brief 14.5): **creator marketplace profile and marketplace status belong to Core (Backend B)**;
account status stays in identity; Instagram tokens and fetched Instagram data stay in Backend A; PAN and bank details
belong to Finance (not collected in round C: Foundation v2 says only at the first paid job).

```
creator app ──/api/core/creator/...──▶ Backend B ──X-API-Key──▶ Backend A /ai/instagram/..., /ai/creators/...
                                          │                         (server to server only; the return URL
                                          ├─ creator package (new)    is never trusted)
                                          ├─ storage (new): signed upload / view URLs, S3-compatible
                                          └─ identity: deletion + anonymisation
```

- New package `com.loofie.core.creator` (domain / app / infra / api), same rules as the others (ArchUnit).
- Identity stays the owner of the account; the creator package owns the passport. Deletion is driven by identity and
  asks the creator package to wipe its data through a port, as `CreatorWork` does today.
- Backend A calls that change data (push the manual profile, delete Instagram data) go through the **outbox**, so a
  Backend A outage never blocks a creator or a deletion. Read calls (login link, connection status) are synchronous
  with short timeouts and answer 503 + Retry-After when Backend A is down.

## 4. Functional gaps (what round C adds)

1. **Onboarding state** for creators: `INCOMPLETE` until the passport's required fields are in; login lands back on
   the form where she left (already the agreed flow).
2. **Passport form** (`GET/PUT /api/core/creator/profile`): display name, city + state, languages (1-3), categories
   (fixed list), formats (reel / static / story / carousel), rate per reel (rupees), Partnership-ads willingness,
   turnaround days (1-30), 1-5 sample links (instagram.com / youtube.com only), bio (optional, 300 chars).
3. **Instagram connect** (phone first, agreed): `POST /api/core/creator/instagram/connect` returns Backend A's 10-minute
   link; after Meta's screen the app polls `GET /api/core/creator/instagram`, which asks Backend A server to server.
   `active` → handle and verified numbers shown read-only, passport numbers `API_VERIFIED`. Failure reasons shown with
   the right message (`not_professional` → manual path, `already_linked` → contact support, `insights_allowed: false` →
   reconnect and allow). Disconnect any time (`DELETE`, data kept on Backend A).
4. **Manual metrics**: followers, average views of the last 10 reels, 1-3 Insights screenshots. Labelled
   `SELF_REPORTED` with the date. Pushed to Backend A (`POST /ai/creators/profile`) for the quality check.
5. **Screenshots upload**: `POST /api/core/uploads` returns a 5-minute signed PUT URL for one image (jpeg / png / webp /
   heic, ≤ 5 MB) under `creator-evidence/<creator id>/<uuid>`; the profile then refers to that key. Ops views it
   through a 5-minute signed GET URL; every view is audited (DECISIONS section 10: viewing creator data is logged).
6. **Ops verification queue**: list creators with unchecked screenshots; mark numbers `OPS_CHECKED` (with corrected
   values if needed) or reject with a reason the creator sees. Audited.
7. **Level and marketplace status**: `level` NEW (default; ₹5,000 per-job cap) / VERIFIED (ops sets it with a reason;
   fast-track proof of three collaborations is the same screenshot upload). `marketplace_status` LISTED / HIDDEN
   (creator pauses herself) / BLOCKED (ops, reason). Separate from account status, as the brief requires.
8. **`CreatorDirectory.bookable(creatorId)`** for F3 and the job engine: account ACTIVE + onboarding complete + LISTED.
9. **Account deletion** (`POST /api/core/account/delete`, confirmed with a fresh OTP to her own phone / email):
   - blocked while she has an active job (creator) or would leave a company without an admin / with live jobs
     (brand), with the reason shown;
   - otherwise in one transaction: status DELETED, phone / email / name cleared (the number can sign up again), all
     sessions ended, marketing consent withdrawn, company membership removed, passport personal fields wiped,
     screenshots queued for deletion from storage; Backend A Instagram data deleted through the outbox
     (`delete_data=true`, retried until done); audit entry `ACCOUNT_DELETED`;
   - jobs, events and audit rows keep the user id (not personal data) so business records stay intact.
   - Ops can delete on a grievance request (reason required, audited).

## 5. Security and abuse

- **The return URL is not proof.** Backend B marks Instagram connected only from Backend A's server-to-server answer.
- `account_ref` = our creator id; the connect call is only for the logged-in creator's own id (no id in the request).
- Status polling: 1 request per second per creator (rate counter), Backend A timeouts 2 s connect / 5 s read.
- Uploads: signed URL fixes content type and maximum size; keys are issued by us and checked by prefix + owner before
  they are accepted in a profile (the F6 rule: never a URL or free text, which ops would open: stored XSS / phishing).
  Ops sees screenshots only through short-lived signed URLs; every view is audited.
- Deletion needs a fresh OTP (a stolen session alone cannot delete the account) and CSRF like every change.
- Sample links: only `https://` on instagram.com / youtube.com; shown to brands as plain text links, never embedded.
- Rate per reel and numbers: range checks (followers 0-100M, views ≥ 0, rate ₹100-₹5,00,000).
- Personal data in Backend B after round C: phone (users), display name, city, bio, handle, screenshots. All wiped on
  deletion; nothing new goes into logs (phone and email are already masked).

## 6. Performance

Onboarding is low volume (a creator fills the form a few times). Hot paths:
- the Instagram status poll (every 2 s while she waits): goes to Backend A each time, so Backend B adds only its own
  overhead; rate-limited;
- passport reads by brands and ops (F3 shortlist later): one indexed row per creator.

**Gate:** passport read p95 ≤ 20 ms at 300 rps over 50,000 creators; Instagram status poll adds ≤ 10 ms over a fake
Backend A at 100 rps; deletion of a creator with 20 sessions and 3 screenshots ≤ 200 ms. Measured with the same
tooling as rounds A-B (warm-up, readiness, safepoint log).

## 7. Scalability and reliability

- Backend A down: login link and status answer 503 + Retry-After; the manual path still works; profile pushes and
  Instagram deletions wait in the outbox and are sent when it is back.
- Storage down: upload URL request answers 503; the rest of the form saves.
- Deletion is one database transaction; the outside effects (Backend A, storage) are outbox rows, retried and visible
  to ops if they keep failing (existing outbox admin).
- Race: deletion vs. accepting a job. Deletion ends every session in the same transaction that sets DELETED, and every
  job action needs a live session, so a deleted creator cannot accept work. Deletion re-checks active jobs under a row
  lock on the user.

## 8. Database (V16)

- `creator_profiles` (user_id PK → users): display_name, city, state, languages[], categories[], formats[],
  rate_per_reel_rupees, partnership_ads, turnaround_days, bio, sample_links[], level, marketplace_status +
  reason, onboarding stage, instagram_handle, instagram_status, instagram_checked_at, version, timestamps.
- `creator_metrics` (creator, metric FOLLOWERS / AVG_VIEWS_10, value, source API_VERIFIED / SELF_REPORTED /
  OPS_CHECKED, captured_on, set_by): one current row per metric + source, so brands see provenance.
- `uploads` (key, owner, purpose, content type, size limit, issued_at, used_at, deleted_at): who may refer to which key.
- `creator_evidence` (creator, upload key, kind INSIGHTS / COLLAB_PROOF, review status PENDING / ACCEPTED / REJECTED,
  reviewer, note, timestamps).
- Outbox: two new kinds, `creator_profile` (→ `POST /ai/creators/profile`) and `instagram_delete` (→ `DELETE
  /ai/instagram/accounts/{ref}?delete_data=true`), plus `storage_delete`.
- Audit actions: PROFILE_COMPLETED, INSTAGRAM_CONNECTED / DISCONNECTED, EVIDENCE_VIEWED / ACCEPTED / REJECTED,
  LEVEL_CHANGED, MARKETPLACE_BLOCKED, ACCOUNT_DELETED, ACCOUNT_DELETED_BY_OPS.

## 9. Failure scenarios

| Scenario | Behaviour |
|---|---|
| Someone types `?instagram=connected` | Nothing changes: status comes only from Backend A |
| Personal Instagram account | `not_professional`: "switch to a free Creator account, or fill in manually" |
| Instagram already on another Loofie account | `already_linked`: support message; ops can free it (Backend A delete) |
| Creator unticks views on Meta's screen | `insights_allowed: false`: followers verified, views not; asked to reconnect |
| Token expires later (Backend A has no webhook) | Seen as `expired` at the next status read; "Reconnect" button; numbers keep their last date |
| Backend A down during connect | 503 + retry; manual path offered |
| Screenshot never uploaded / wrong owner's key | Profile refuses the key (422) |
| Deletion while a job is active | 409 with the job list; she finishes or ops reassigns first |
| Last admin of a company with teammates deletes | 409: make someone else admin first |
| Backend A unreachable at deletion | Account deleted at once; Instagram deletion retried from the outbox until done |

## 10. Test coverage (to add)

- `CreatorOnboardingTest` (HTTP + Postgres, fake Backend A): manual path end to end; Instagram path with each status
  and failure reason; forged return URL ignored; onboarding resumes after re-login; bookable rules.
- `UploadTest`: signed URL fields; key ownership; wrong type / size refused; ops view audited.
- `OpsCreatorTest`: verification queue, OPS_CHECKED, reject reason, level and block with reasons, audit.
- `AccountDeletionTest`: fresh OTP needed; blockers (active job, last admin); everything wiped; number can sign up
  again; outbox rows for Backend A and storage; sessions ended; race with job accept.
- `FullFlowCheck` extended (creator onboarding + deletion), load runs for the gates in section 6.

## 11. Improvement plan: decisions needed

| # | Decision | Recommended |
|---|---|---|
| **C1** | File storage for screenshots (none exists) | **Build a small S3-compatible signed-upload module now** (AWS S3 Mumbai or Cloudflare R2), with a local fake; F6 photos and F7 videos reuse it. Needs the hosting owner to create a bucket and keys. Alternative: accept keys only and leave uploads to F7, which leaves the manual path unusable until then. |
| **C2** | Where passport numbers live | Backend B keeps the business fields (rate, formats, turnaround, categories, city, languages, links, level, status) and the self-reported / ops-checked numbers with their source; **API_VERIFIED numbers stay in Backend A** and are read from it. No copy to drift. |
| **C3** | Category list | Use Backend A's list so matching and filters agree. Today it is only `beauty_skincare`, `personal_care`, `other`; the pilot niche (open question 1 in Foundation v2) decides more. |
| **C4** | Marketplace visibility of self-reported creators | Listed at once with the `SELF_REPORTED` label (ops curates every shortlist in v1 anyway); the screenshot check happens in the ops queue. Alternative: hidden until ops checks, which slows supply. |
| **C5** | Level VERIFIED | Ops sets it with a reason (two on-time jobs or three collaboration proofs). Automatic promotion after two on-time jobs waits for F5 job-outcome data in a later pass. |
| **C6** | Job engine gate | A creator who is not bookable (incomplete, HIDDEN, BLOCKED, deleted) cannot accept an invitation: the job engine calls `CreatorDirectory.bookable` on accept. |
| **C7** | Deletion re-confirmation | Fresh OTP to her own phone / email (not just the session). |
| **C8** | Split | Build in two parts with one approval: **C1 passport + Instagram + manual + uploads + ops queue**, then **C2 deletion**. Each part tested before the next. |

### Needed from Backend A (Aditya)

1. **DECISIONS.md section 10 still describes Instagram before the phone** (draft creator, 1-hour cleanup job). The team
   agreed phone first, so there are no phone-less drafts and no cleanup job. Please align the text.
2. `GET /ai/creators/{ref}` by **our creator id** (today by handle only; handles change, and manual creators may have
   none yet).
3. A way to record **`OPS_CHECKED`** numbers (`POST /ai/creators/profile` accepts only `manual_profile` today).
4. **Delete a creator's profile** data sent through `POST /ai/creators/profile` (handle, bio, captions) when an account
   is deleted: `DELETE /ai/instagram/accounts/{ref}?delete_data=true` covers only Instagram-fetched data.
5. The category list for the pilot niche (C3).

### Not in round C

PAN / bank / KYC (Finance, at the first paid job), automatic level promotion, F3 shortlist screens, video uploads
(F7), real SMS / email providers and production secrets (team / hosting).

## 12-13. Part 1: what was built (2026-11-18)

| Piece | Where |
|---|---|
| V16: `creator_profiles`, `creator_metrics`, `uploads`, `creator_evidence` (no FK to `users`: identity keeps its tables) | `db/migration/V16__f1_creator_onboarding.sql` |
| Storage (C1): S3-compatible presigned URLs, AWS Signature V4 written in-house (no SDK) and checked byte for byte against AWS's published example; `none` (503) / `s3` / `local` (dev and tests only; production refuses it) | `storage/` (`SigV4`, `S3ObjectStore`, `LocalObjectStore`, `Uploads`) |
| Upload register: every key we issue, its owner, purpose, type and limit; a profile accepts only an issued, unused key of hers that really holds a file ≤ 5 MB (a presigned PUT cannot cap size, so it is checked on attach) | `storage/Uploads` |
| Passport: form (partial saves), completion rule in one place, pause / resume, numbers with source and date | `creator/app/CreatorProfileService`, `creator/domain/CreatorForm` |
| Instagram connect: Backend A's link; status only from Backend A server to server; verified handle cannot be overwritten by typing; disconnect | `creator/app/InstagramConnectService`, `creator/infra/BackendAInstagramClient` |
| Screenshots: ticket → PUT → attach; removal keeps at least one behind self-reported numbers | `creator/app/CreatorEvidenceService` |
| Ops: queue, 5-minute view link (audited), accept / reject with note, OPS_CHECKED numbers, level, block; every look and change audited | `creator/app/OpsCreatorService`, `/api/core/ops/creators/...` |
| Profile to Backend A via the outbox (`core.creator.v1` → `POST /ai/creators/profile`), only when complete and something changed | `messaging/BackendACreatorCalls`, `AiFeedbackSender` |
| Job gate (C6): ACCEPT by a creator who is not bookable → 422 | `job/domain/CreatorEligibility`, `creator/app/CreatorDirectory` |
| `identity/app/AccountDirectory`: account active / onboarding flag for other features | identity |

Endpoints (creator): `GET/PUT /api/core/creator/profile`, `PUT /creator/listing`, `POST /creator/instagram/connect`,
`GET/DELETE /creator/instagram`, `POST /creator/uploads`, `POST /creator/evidence`, `DELETE /creator/evidence/{id}`.
Ops: `GET /ops/creators/evidence-queue`, `GET /ops/creators/evidence/{id}/link`, `PUT /ops/creators/evidence/{id}`,
`GET /ops/creators/{id}`, `PUT /ops/creators/{id}/metrics|level|block`.

Configuration: `LOOFIE_STORAGE_MODE` (`none` default) and `LOOFIE_STORAGE_ENDPOINT / REGION / BUCKET / PATH_STYLE /
ACCESS_KEY / SECRET_KEY`; `loofie.creators.categories`, `require-bookable-to-accept` (true; off only in test / local
where dev headers use made-up creator ids). Backend A calls reuse `AI_FEEDBACK_URL` / `AI_FEEDBACK_API_KEY`.

## 14. Part 1 validation

**Tests: 316 run, 0 failures, 2 skipped (Backend A contract test).** New in part 1 (16):

| Test | Proves |
|---|---|
| `SigV4Test` (3) | AWS's documented presigned-URL example reproduced exactly; content type signed for uploads; RFC 3986 encoding |
| `CreatorPassportTest` (11, HTTP + Postgres, fake Backend A, local store) | manual path end to end with a real signed upload, completion sets `/me` onboarding, audit, profile reaches Backend A as `manual_profile` · progress survives logout / login · form checks (links, category, rate, handle) and required fields cannot be emptied once complete · only creators (403 brand, 401 anonymous) · pause / resume · upload rules (type, someone else's key, never-issued key, not uploaded, tampered URL, wrong type to the store, used twice, over 5 MB) · last screenshot behind self-reported numbers cannot be removed, removal queues the file for deletion · **Instagram: nothing changes until Backend A says so (forged return URL), syncing → active, completes with verified numbers shown as API_VERIFIED, typed handle cannot replace the verified one, insights-not-allowed message, disconnect; repeated polls queue nothing** · Backend A down → 503 + Retry-After, manual path still works · ops queue, audited view link that really serves the file, reject needs a note, accept once, OPS_CHECKED, level needs a reason, block / unblock (she cannot lift a block), every action audited, creators get 403 on ops |
| `CreatorGateTest` (1, gate on) | no passport → cannot accept; complete but paused → cannot; listed → accepts; suspended account → cannot |
| `ArchitectureTest` (+1 rule) | nothing outside `creator` touches `creator.infra` |

**Found while testing and fixed:** `instagram_status` column one character too short for `NOT_CONNECTED`; every status
poll queued a profile push to Backend A (15 per connection at a 2 s poll): now only on completion or a real change.

**Load (gates in section 6), 2026-11-18 ~12:00 IST, 50,000 complete creators with sessions, fake Backend A on the
same machine (`scripts/perf/creator-seed.sql`, `CreatorLoadTest.java`, `FakeBackendA.java`), measured twice on fresh
creators after a discarded warm-up:**

| Phase | Run 1 | Run 2 | Gate |
|---|---|---|---|
| Passport read (cookie), 300 rps × 15,000 | p95 2 ms, all 200 | p95 3 ms, all 200 | ≤ 20 ms **PASS** |
| Instagram status poll through Backend B vs. Backend A direct, 100 rps × 3,000 | +7 ms (p95 7 vs 0), all 200 | +5 ms (p95 6 vs 1), all 200 | ≤ 10 ms added **PASS** |

Worst time-to-safepoint 0 ms (no CPU starvation). Outbox: exactly one profile push per creator whose Instagram status
changed, none for repeated polls. The polls measured are the worst case (each one changes her status and writes).

**End-to-end** (`scripts/e2e/FullFlowCheck.java`, real app): **59 of 59** with the new creator steps (passport form,
CSRF, ticket, real PUT to the signed URL, attach, complete and bookable, `/me` onboarding done); no errors in the log.

### Part 1 Definition of Done

| Item | State |
|---|---|
| Passport form, completion, pause / resume | **Done, tested, load-tested** |
| Instagram connect via Backend A, return URL never trusted | **Done, tested** against a fake Backend A; **real Meta flow untested** (Meta App Review not submitted; Backend A) |
| Manual path with screenshots, signed uploads | **Done, tested** with the local store; **real S3 / R2 untested** until the bucket exists (hosting) |
| Ops queue, OPS_CHECKED, level, block, audit | **Done, tested** |
| Job gate C6 | **Done, tested** |
| Unused uploads and removed screenshots deleted from storage | **Done in part 2** (`UploadCleanup`) |
| Backend A asks (section 11: creator by id, OPS_CHECKED source, delete profile, DECISIONS text, categories) | **Waiting on Backend A** |

## 15. Part 2: account deletion (2026-11-18)

**How it works.** `POST /api/core/account/delete/start` checks what is in the way, then sends a code to her own phone
/ email (purpose `DELETE`, V17); `POST /api/core/account/delete {challengeId, code}` deletes, ends every session and
clears this browser's cookie. Ops: `POST /api/core/ops/accounts/{id}/delete {reason}`, founder or ops lead only, same
blockers. Staff accounts are not deleted here (they are closed by suspension).

**In one transaction** (`identity/app/AccountDeletionService`): account DELETED with phone, email, verification dates
and authenticator cleared (the number can sign up again) · every session ended · marketing consent withdrawn (consent
records kept as proof) · recovery codes, OTP challenges and the contact's rate counters deleted · company membership
removed · every `PersonalDataHolder` erased: creator passport (name, city, state, bio, links, handle, numbers; hidden
for good), screenshots taken down, all her uploads queued for removal from storage, and an `instagram_data_delete`
outbox row (→ `DELETE /ai/instagram/accounts/{ref}?delete_data=true`, retried until Backend A confirms; 404 counts as
done) · audit `ACCOUNT_DELETED` / `ACCOUNT_DELETED_BY_OPS` with the company she left. Jobs, events, consents and the
audit log keep the user id only.

**Blocked (409 `deletion-blocked`, with `jobIds`)** while: as a creator, any job of hers is not closed / refunded /
cancelled (this includes a pending invitation and payouts not yet closed, so money owed blocks it); as a brand user,
she is the only admin of a team with other members (hand over first), or the last member of a company with unfinished
jobs. The company row is locked during the check, so a teammate cannot leave at the same moment.

**Storage cleanup** (`storage/UploadCleanup`, every 10 min on one instance, lease `upload-cleanup`): removes files
queued for deletion and uploads never attached an hour after their ticket expired; calls storage with no transaction
open and marks a file deleted only after storage confirmed, so failures retry.

### Part 2 validation

**Tests: 321 run, 0 failures, 2 skipped.** New: `AccountDeletionTest` (5, HTTP + Postgres, fake Backend A, local
store): fresh code needed (anonymous 401, wrong code counted and nothing deleted, CSRF required, a login code for the
same phone cannot delete) · everything listed above verified row by row, cookie cleared, `/me` 401 · Backend A gets the
delete-with-data call through the relay · the file is gone from storage after the cleanup · the same phone signs up
again as a new account · a creator with a PAYOUT_PENDING job is blocked with the job id · the only admin must hand over;
after handing over she is deleted and the teammate stays admin; the last member is blocked by an unfinished company job
and can go once it is closed; the company record stays · ops deletion: OPS 403, empty reason 422, OPS_LEAD 204 with
audit, a second deletion 404.

**Load (gate in section 6), 2026-11-18 ~12:45 IST** (`scripts/perf/deletion-seed.sql`, `DeletionLoadTest.java`):
creators with **20 live sessions and 3 screenshots each**, deleted through the ops endpoint (the same transaction as a
self-deletion), after a discarded warm-up:

| Set | Deletions | p50 | p95 | p99 | Answers | Gate p95 ≤ 200 ms |
|---|---|---|---|---|---|---|
| 1 | 600 at 20/s | 17 ms | 54 ms | 175 ms | all 204 | **PASS** |
| 2 | 600 at 20/s | 14 ms | 28 ms | 48 ms | all 204 | **PASS** |

Data check after the run: every deleted creator has 0 live sessions, 0 screenshots showing, 0 files not queued; one
Instagram deletion queued per creator. Worst time-to-safepoint 5 ms.

**End-to-end** (`FullFlowCheck`): **64 of 64**, with deletion (fresh code, wrong code changes nothing, session gone,
same phone signs up again); no errors in the app log.

### Round C Definition of Done

| Item | State |
|---|---|
| Passport, Instagram connect, manual path with screenshots, ops queue, job gate | **Done, tested, load-tested** (part 1) |
| Account deletion with fresh code, blockers, ops deletion | **Done, tested, load-tested** |
| Files removed from storage (taken down, deleted accounts, unused uploads) | **Done, tested** (local store) |
| Instagram data deleted at Backend A | **Done, tested** against a fake Backend A |
| Manual-profile data sent to Backend A (`POST /ai/creators/profile`: handle, bio, followers) deleted on account deletion | **Waiting on Backend A**: there is no endpoint for it yet (ask 4 in section 11) |
| Real S3 / R2 and real Meta flow | **Untested until the bucket exists (hosting) and Meta App Review (Backend A)** |
| Confirmation message after deletion | **Not sent**: the contact is erased in the same transaction; the app shows the confirmation. Revisit with the lawyer-reviewed notice |

### Known limits

- A pending invitation blocks deletion (she declines it first); the message says so in general terms.
- `rate_counters` for her contact are deleted with the account, so a deleted number starts with a fresh OTP allowance.
- Session rows (revoked) and OTP rows elsewhere follow the existing retention (7 days / 1 day).
