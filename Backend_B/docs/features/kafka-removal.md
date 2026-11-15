# Kafka removal: outbox to `/ai/feedback` over HTTP

Status: **built, tested and measured (steps 1–15), 2026-11-15.** Results and limits at the end.
Decided by Rishabh on 2026-11-15: one Spring Boot monolith (Core) plus the separate AI service
(Backend A); no Kafka for now. Kafka or a split into services can come back when a measurement asks for it.

## 1. What exists today

- Job changes and timer notices are written to `outbox` in the same transaction as the change
  (`JobEventRecorder`, `JobNoticeService`). Two event types: `JobStateChanged`, `JobDeadlineNotice`.
- `OutboxRelay` sends rows to Kafka topic `core.job.v1`: per-job order (waves by `message_key`), backoff
  1 s → 60 s, stops the run when the broker is down, one instance at a time (leader lease), no
  transaction held while sending. `OutboxMonitor` exports backlog and oldest-age metrics.
- Kafka-only code: `KafkaConfig` (consumer retry + dead-letter topic), `Inbox` / `IncomingEvent` /
  `processed_events` (consumer de-duplication). **No consumer exists in the app**: they are used only by tests.

## 2. Who reads Kafka

Nobody. Checked on 2026-11-15: all 142 files of `origin/backend-a` (`366bd24`). No Kafka client, no
broker of any kind, `docker-compose.yml` has only their Postgres and their API. Backend A takes events
over HTTP: `POST /ai/feedback` (types `job_state_changed`, `job_timer_fired`, ...), as agreed with Aditya.

So today Core publishes to a broker that has no reader, and the planned HTTP forwarder to Backend A
does not exist yet. Kafka costs infrastructure, local setup (WSL broker) and test time, for no reader.

## 3. Target

Keep the outbox (it is what guarantees nothing is lost). Replace only the transport:
`outbox → relay → HTTP POST /ai/feedback`. Same guarantees as today:

| Guarantee | How |
|---|---|
| Nothing lost | Row stays unpublished until Backend A returns 2xx |
| No duplicates saved | We send our `event_id`; Backend A returns `duplicate: true` on a retry (counts as success) |
| Order per job | Existing per-key waves, unchanged |
| A Backend A outage never blocks a transition | Relay runs after commit, outside the transaction (unchanged) |
| A slow Backend A never holds a DB connection | No transaction open while sending (unchanged) |
| One sender at a time | Leader lease (unchanged) |

## 4. Mapping (outbox envelope → `/ai/feedback` request)

Done at send time by one pure function (`AiFeedbackMapper`), so the outbox stays generic and a Backend A
contract change also applies to rows still waiting.

| Our event | `event_type` | Payload sent |
|---|---|---|
| `JobStateChanged` with a creator | `job_state_changed` | `creator_ref`, `job_id`, `from`, `to`, `reasonCode` + `faultParty` when set, `issue_type` (from `deliveryIssueType`), shipment facts (`shipmentAttempt`, `deliverySource`, `olderShipmentReceived`) |
| `JobDeadlineNotice` with a creator | `job_timer_fired` | `creator_ref`, `job_id`, `kind`, `audience`, `due_at` |
| Either, with no creator (e.g. `AWAITING_PAYMENT → PAID`) | not sent | Marked done as `SKIPPED`: Backend A requires a creator, and these teach the models nothing |

Envelope fields: `event_id` = our event id, `occurred_at` = our time (UTC, ISO 8601), `subject_type` =
`job`, `subject_id` = job id, `actor_type` = `brand` / `creator` / `ops` / `system` from our role.

**Needed change:** the payloads do not carry the creator id today (only `assignmentId`). Add `creatorId`
to both payloads when they are written. It is our internal id (Backend A asks for exactly this as
`creator_ref`), not personal data.

**Never sent:** the ops free-text reason (already removed), `actorId`, `orderId`, `assignmentId`.
Backend A refuses PAN, bank, phone, email, payout amount with 422; none of these are in our payloads.

**Not in this change:** `job_outcome` (needs revision count, dispute flag and rating, which arrive with
F7/F8); `brief_ref` and `deadline_at` (no brief link on jobs yet). Backend A accepts events without them.

## 5. Failure handling

| Backend A answer | Meaning | What the relay does |
|---|---|---|
| 2xx (incl. `duplicate: true`), 409 | Saved (409: already saved with other content) | Mark published (409 also counted in `loofie.outbox.conflicts`) |
| 422, 400, 404 | This row will never be accepted (a mapping bug or a contract change) | **Park** the row: out of the queue, error kept, metric + alert. The job's later rows continue. Fix, then requeue parked rows with one SQL statement |
| 401, 403 | Our API key is wrong or rotated | Retry with backoff (fixing the key fixes it), alert |
| 429, 5xx, timeout | Backend A busy or down | Retry with backoff (1 s → 60 s), order per job kept |
| Connection refused / DNS failure | Backend A unreachable | Stop the run (same as "broker down" today), retry next run |
| Not configured (no URL) | Backend A not deployed in this environment | Relay off; rows wait and are sent when it is configured (backfill) |

**Why park instead of retrying forever:** one bad row would otherwise block that job's events forever
and keep the alert permanently red. Parked rows are kept 30 days (published rows: 7) for investigation.

**Concurrency cap:** at most `max-in-flight` requests to Backend A at once. The Kafka relay sent a whole wave
(up to 100) at once, fine for a broker but too many for one FastAPI server on a GPU box. Planned default 16;
**built with 32** after measuring (16 gave 587 events/s, below target; FastAPI runs sync handlers on 40 threads).
**409 changed during the build:** planned as "park", built as "delivered". Backend A answers 409 only when it
already has that event id, so it has the event; parking it would just repeat the 409 on every requeue.

## 6. Database change (V8, expand only)

- `outbox.parked_at timestamptz NULL`. Queue query and backlog index become
  `published_at IS NULL AND parked_at IS NULL`; new partial index for parked rows.
- `processed_events` is no longer used. **Not dropped in V8**: a still-running old instance (rolling
  deploy) has retention code that deletes from it. Dropped in a later migration after this release is live.
- The `topic` column stays (names the stream). New rows keep `core.job.v1`. No data change.

## 7. Layers

All in `messaging` (infrastructure), no change to `job` domain/app except adding `creatorId` to the two
payloads. ArchUnit rules unchanged. The HTTP client is the JDK `java.net.http.HttpClient` already used by
the perf scripts: **no new dependency**. Removes `spring-boot-starter-kafka` and its test starter.

## 8. Files

- **Remove:** `KafkaConfig`, `Inbox`, `IncomingEvent`, `Topics` (one constant moves to `Outbox`),
  `AlwaysFailingTestListener`, `KafkaTestClient`, `ConsumerErrorHandlingTest`, `BrokerDownTest` (replaced),
  `@EmbeddedKafka`, `scripts/local/kafka.sh`, `scripts/local/install-kafka-wsl.sh`, all `spring.kafka`
  and `loofie.kafka` config, `KAFKA_BOOTSTRAP_SERVERS` in prod.
- **New:** `AiFeedbackMapper`, `AiFeedbackClient` (HTTP, timeouts, API key header), `V8__outbox_http_relay.sql`.
- **Changed:** `OutboxRelay` (sender instead of `KafkaTemplate`, parking, cap), `OutboxMonitor` (parked
  gauge), `RetentionJob` (parked rows 30 d, no inbox), `JobEventRecorder`, `JobNoticeService`, config,
  `OutboxRelayTest`, `IntegrationTest`, docs (`production-readiness.md`, `architecture-layers.md`,
  `f5-job-engine.md`, integration doc).
- **Config:** `loofie.ai-feedback.base-url`, `.api-key` (env `AI_FEEDBACK_API_KEY`, never in the repo),
  `.connect-timeout` 2 s, `.request-timeout` 5 s, `.max-in-flight` 16.

## 9. Tests (all against a fake Backend A: the JDK's built-in HTTP server, no new dependency)

1. A state change is sent once with the exact `/ai/feedback` shape, `X-API-Key` header and our `event_id`.
2. A timer notice becomes `job_timer_fired`.
3. An event with no creator is skipped, never sent.
4. The free-text reason, `actorId` and order/assignment ids are never in a request.
5. `duplicate: true` counts as delivered.
6. 422 parks the row, and the same job's next event still goes out.
7. 503 backs off, then succeeds on retry, and the job's events arrive in order.
8. Connection refused: the run stops fast, nothing is marked sent (replaces `BrokerDownTest`).
9. 401 retries and does not park.
10. Never more than `max-in-flight` requests at once.
11. Rows written before the URL is configured are sent once it is (backfill).
12. Retention: parked rows kept 30 days, published rows 7.
13. Existing suite (133) still passes without any Kafka on the machine.
14. **Contract check (manual, once):** run against Backend A's own mock (`uv run loofie mock`), which
    applies the real `/ai/feedback` rules, and confirm 0 rejections.

## 10. Measurement (step 14)

Relay throughput against the fake server with 20 ms per request, 1M-job data set: the relay must drain
faster than the peak event rate. Target: ≥ 1,000 events/s (500 transitions/s plus automatic follow-ups and
notices). Also re-run `LoadTest` at 500 rps to confirm transitions are unaffected (target p95 ≤ 300 ms,
today 5 ms).

## 11. Risks and limits

- **Backend A capacity is unknown.** If `/ai/feedback` cannot take ~1,000 requests/s, the backlog grows
  during peaks (nothing is lost; the AI just learns later). Ask Aditya for their limit; a batch endpoint
  (`POST /ai/feedback/batch`) is the fix if needed, not more Core instances.
- **A long Backend A outage piles up rows** (about 1 KB each; at an average 100 events/s one day is ~9M rows, ~9 GB). Alert on
  oldest age > 15 min. Not a correctness risk.
- **No second consumer.** If another service later needs Core's events, it either calls an HTTP endpoint
  we add, or that is the measurement that brings a broker back.
- **Conflict with the product brief (section 14.3–14.4):** it lists Kafka for async communication and a
  microservices architecture. This change follows today's decision (monolith, no Kafka for now). The brief
  needs the founder's update; Foundation v2 does not mention Kafka.

## Decisions needed

1. **When Backend A is not configured, queue the events and send them later (recommended), or drop them?**
2. **From Aditya:** how many `/ai/feedback` requests per second their server can take, the production URL,
   and Core's API key (sent privately).
3. **`job_outcome` waits for F7/F8** (it needs revision count and rating): OK?

---

## 12–15. Built, tested, measured (2026-11-15)

### Tests: 146 (145 pass, 1 opt-in skipped)

`OutboxRelayTest` (16, against `FakeAiService`, the JDK's HTTP server): agreed shape with key and our event id,
follow-ups after their cause (8 jobs), `job_timer_fired`, no-creator events done but never sent, ops note and
internal ids never sent, duplicate = delivered, 409 = delivered, 422 parks and the job's next event still goes,
503 backs off then delivers in order, 401 retried not parked, unreachable stops the run in under 3 s with nothing
lost, never more than `max-in-flight` open, rows written while unconfigured are sent once configured.
`AiFeedbackMapperTest` (6): codes, nulls left out, actor types, skip rules, HTTP classification.
`RetentionJobTest`: parked rows kept 30 days. **`BackendAContractTest`** (opt-in, `AI_CONTRACT_URL`): run against
Backend A's own mock (branch `366bd24`): 7 events of every kind accepted, every resend a duplicate, wrong key 401.

### Measurements (laptop: app, PostgreSQL, fake Backend A and load generator on one machine; 1M jobs, 2.3M outbox rows)

| Run | Result |
|---|---|
| Relay throughput, 60k events (20k jobs x 3), Backend A 20 ms per request, 16 in flight, batch 100 | 587 events/s: **below the 1,000 target** (16 / 20 ms caps it at 800) |
| Same, 32 in flight, batch 100 | 959 events/s (a batch of 100 leaves the last group of 32 part-empty) |
| **Same, 32 in flight, batch 200 (the defaults now)** | **1,145 events/s, 0 failures, 0 parked, 0 of 20,000 jobs out of order** |
| Engine at 500 rps, realistic mix, relay **off** | p95 3 ms, 0 failed, PASS |
| Engine at 500 rps, realistic mix, relay **on** (events flowing to fake Backend A) | p95 2 ms, 0 failed, PASS; 4,514 events all sent, write-to-send p95 0.49 s |
| Relay queue query on 2.3M outbox rows | 2.2 ms, uses `outbox_unpublished_seq_idx` |
| Kafka libraries in the build | 0 of 146 dependencies |

Not a pass: a first 500 rps run started straight after boot with the heavy ops mix (25% lists) gave p95 989 ms
and 962 shed requests. Same result with the relay off in F5 (lists are shed by design, and the cold start is the
known warm-up gap). With a warm-up and the realistic mix, relay on and off are the same.

### Found while building

1. **Metric name clash:** a counter `loofie.outbox.parked.total` and the gauge `loofie.outbox.parked` map to the
   same Prometheus name, so one was silently not exported. Renamed the counter `loofie.outbox.rejected`.
2. **Relay pause after a crash:** an instance that dies without shutting down keeps the relay lease until it
   expires (120 s). Events wait (nothing lost), then the next instance takes over. A normal deploy releases it.

### Definition of Done

| Item | State |
|---|---|
| Kafka gone (code, config, tests, scripts, dependencies) | Done, verified (0 Kafka jars) |
| Nothing lost, order per job, no duplicates saved, outage never blocks a transition | Done, tested |
| Contract with Backend A | Done against their mock; **not yet against their running server** |
| Throughput >= 1,000 events/s | Done at 20 ms per request; **real Backend A latency unknown** |
| Engine unaffected | Done, measured |
| Alerts | Metrics exist (`loofie.outbox.parked`, `.oldest.age.seconds`, `.failures`, `.rejected`); **alert rules need hosting** |
| Docs and integration notes | Done |

### Known limits

- **Backend A's real capacity and latency are unknown.** Throughput = in-flight / latency: at 100 ms per request
  32 in flight gives ~320/s. Nothing is lost if it is slower; the AI learns later. Fix if needed: a batch endpoint
  on their side, not more Core instances.
- **Relay pause up to 120 s after a crash** (lease expiry). Acceptable for AI feedback.
- **One receiver.** A second consumer of Core's events needs its own sender; a broker comes back only if a
  measurement asks for it.
- **`processed_events` table still exists** (unused). Drop it in a later migration once this release is live.
- **`job_outcome`, `brief_ref`, `deadline_at` not sent yet** (F7/F8 and briefs).
