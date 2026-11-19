# Upload size limit at the storage (open ask 45): plan

Status: **approved 2026-11-19 as recommended (option B, `sizeBytes` required now, 20 links per hour per person);
built and tested, results in section 9.**

## 1. Business purpose

Creators upload evidence screenshots, draft videos (up to 500 MB) and delivery photos straight to the storage bucket
with a signed link. The bucket is paid by the gigabyte. A file must never be bigger than its purpose allows, and
nobody should be able to fill the bucket.

## 2. What exists today

- `Uploads.issue` gives a signed **PUT** link (5 minutes; 60 for videos) that fixes the key and the content type
  (`SigV4` signs `content-type;host`).
- The size limit (5 MB evidence, 500 MB video, 10-50 MB photos) is checked **only when the key is attached** (`stat`
  on the stored file). Files never attached are deleted an hour after their link expires (`UploadCleanup`).
- Ticket limits: evidence 20 per hour per creator. **Draft videos and job photos have none.**

## 3. Findings

| # | Finding | Effect |
|---|---|---|
| G1 | A signed PUT does not limit size. S3 accepts up to 5 GB in one PUT | One link can put 5 GB in the bucket (10 times the video limit); it stays up to about 2 hours before cleanup |
| G2 | Draft-video and job-photo links have no rate limit | A creator (or a stolen session) can request links in a loop: 1,000 links × 5 GB. Storage cost and bucket abuse, bounded only by bandwidth |
| G3 | Our local stand-in store caps at 600 MB, not per purpose | Tests do not show the real limit behaviour |

## 4. Options

| | A. Presigned POST with a policy | **B. Sign the exact size on the PUT (recommended)** |
|---|---|---|
| How | Browser sends a form (fields + file); the policy says `content-length-range 1..max` | App declares the file size when asking for the link; we refuse above the limit and sign `content-length`; the storage refuses any other size |
| Works on | AWS S3, MinIO. **Not Cloudflare R2** (no POST) | S3, R2, MinIO: signed headers are standard SigV4 |
| App change | Larger: multipart form instead of a PUT, new fields in the ticket | Small: send `sizeBytes` with the request; the upload stays a PUT. Browsers and mobile HTTP clients set `Content-Length` from the file themselves |
| Limit | A range | Exact size, which is stricter |

## 5. Plan (option B)

| # | Change | Where | Size |
|---|---|---|---|
| U1 | Ticket requests take `sizeBytes` (required). Refused with 422 when 0 or above the purpose's limit, before any link is made | `Uploads.issue`, the 3 controllers (`CreatorController`, `DraftControllers`, `JobUploadController`), services | S |
| U2 | `SigV4` signs `content-length` too, so S3 / R2 refuse a body of any other size (403) | `SigV4`, `S3ObjectStore.presignPut` | S |
| U3 | The ticket's `headers` list `Content-Length` (for clients that need it; browsers set it themselves) and the stored row keeps the declared size; attach also checks stored size = declared size | `Uploads`, `uploads` table (new column, migration V20) | S |
| U4 | Local stand-in store enforces the same rule (signed size, refused otherwise) | `LocalObjectStore`, `LocalStorageController` | S |
| U5 | Rate limit on every link: 20 per hour per user across all purposes (the evidence limit, shared) | `Uploads.issue` via `RateLimits.perUser` | S |
| U6 | Contract note for the app developers: send `sizeBytes`; do not set `Content-Length` by hand in a browser | `docs/integration/`, F1 round C and F7 docs | S |

## 6. Proof

- Unit: `SigV4` with `content-length` (signature changes with the size); `Uploads` refuses 0, negative, too big,
  missing; rate limit at 21 links.
- HTTP + Postgres: each upload flow (evidence, draft video, receipt photo) with the real local store: right size
  works end to end; a bigger and a smaller body are refused by the store; attach still works.
- **Real S3-compatible store**: an opt-in test against MinIO in Docker, signed URL from our code, proving the store
  itself refuses a body of a different size (the claim the whole fix rests on).
- Full suite and e2e. Load: ticket endpoint p95 within the 300 ms write gate.

## 7. Decisions needed

1. **Option B** (recommended) or A.
2. **`sizeBytes` required at once** (recommended: no app is live yet), or optional for a transition period (then
   G1 stays open until it is required). If app developers already call these endpoints, they need the U6 note first.
3. **20 links per hour per user** across all purposes (recommended), or a separate, higher limit for videos.

## 8. Will stay open

R2 has no India data location (hosting decision: S3). A file can still be uploaded and never attached; it is removed
an hour after its link expires, as today.

## 9. Results (2026-11-19)

Built as planned (U1-U6). Migration `V20__upload_declared_size.sql` adds `uploads.declared_bytes`.

| Check | Result |
|---|---|
| Full suite | 357 run, 0 failures, 4 skipped (2 Backend A contract tests, 2 real-store tests that need a store) |
| `CreatorPassportTest.uploadRules` | no size / 0 / over 5 MB refused before any link; ticket headers carry `Content-Length`; one byte more, one byte less, a URL with an edited size and a wrong type all refused by the store (403) and nothing stored; exactly 5 MB accepted |
| `CreatorPassportTest.uploadLinksAreLimitedTo20AnHourPerPersonAcrossPurposes` | 20 links, the 21st answers 429 with Retry-After; another person unaffected |
| `DraftFlowTest` | over 500 MB gets no link; every video flow still works end to end with real ffmpeg |
| `SigV4Test` | `content-length;content-type;host` signed; another size gives another signature |
| **`S3SizeLimitTest` against a real S3-compatible store** (LocalStack 3.8 S3, signature validation on, in Docker) | 1,001, 999 and 50,000 bytes on a 1,000-byte link: 403, nothing stored; exactly 1,000: 200 and stored; wrong content type: 403 (proves the store really checks signatures). MinIO was the plan, but its images are no longer public. Re-run against AWS S3 once the bucket exists |
| End-to-end check | passed (exit 0), including the real upload step |

Not measured: the ticket endpoint under load. The change adds one rate-counter write per link, the same write the
evidence links already did. Measure it with the next load run on the real server.

**For app developers:** every upload link request (`POST /api/core/creator/uploads`,
`POST /api/core/creator/jobs/{id}/drafts/upload`, `POST /api/core/jobs/{id}/uploads`) now needs `sizeBytes`, the
file's exact size in bytes. Upload with the `url`, `method` and `Content-Type` from the answer. A browser sets
`Content-Length` from the file itself; do not set it by hand (browsers forbid it). Native clients send the
`Content-Length` from `headers`.
