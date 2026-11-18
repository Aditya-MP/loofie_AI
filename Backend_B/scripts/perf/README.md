# Performance scripts

Measure before optimising. These load a realistic data set and measure the hot queries and the API.

| File | What it does |
|---|---|
| `load-data.sql` | Fills the **test schema** `core_test` with 1M jobs (250k orders, 2,000 brands), 1M assignments, 2M events, 2M outbox rows, 400k notices. About 10 minutes. The next `mvn test` wipes it. |
| `explain.sql` | `EXPLAIN ANALYZE` of every hot query: engine, timers, relay, ops list, job detail, retention. |
| `LoadTest.java` | Open-loop HTTP load (fixed request rate, so a slow server shows up as latency). Mix: job detail, job list, transitions. Exits 1 unless p95 <= 300 ms and 0 failed (the gate). |
| `F6LoadTest.java` | F6 actions under load: brands mark jobs shipped, then creators confirm receipt with a photo. Same gate. |
| `FakeAiServer.java` | Stand-in for Backend A's `/ai/feedback`: answers 200 after a fixed delay, prints requests/s and the most open at once. |
| `relay-backlog.sql` | 60,000 waiting job events (20,000 jobs x 3) for measuring how fast the outbox relay drains. |
| `auth-seed.sql` | F1: 100,000 creators with one live session each (cookie `perf-session-<i>-padpadpad`) in `core_test`. |
| `brand-seed.sql` | F1 round B: companies with an admin session each (cookie `perf-brand-session-<i>-padpadpad`) and accepted jobs spread over them. |
| `BrandLoadTest.java` | F1 round B: brand inbox (dev headers vs session cookies) and "Shipped" with cookie + CSRF. Start the app and wait for `/actuator/health/readiness` = 200 (the startup query warm-up runs before it), then a gentle warm-up and a 20 s pause before measuring. |
| `AuthLoadTest.java` | F1: what login adds per request (same endpoint, dev headers vs session cookies) and OTP send / verify / signup. Reads codes from the app log (fake sender). Run the app with the per-IP OTP limits raised, since all load comes from one IP. |

```bash
PSQL="/c/Program Files/PostgreSQL/18/bin/psql.exe"
PGPASSWORD=core_local "$PSQL" -h localhost -U core_app -d core_db -f scripts/perf/load-data.sql
PGPASSWORD=core_local "$PSQL" -h localhost -U core_app -d core_db -f scripts/perf/explain.sql > explain.out

# job ids to hit
PGPASSWORD=core_local "$PSQL" -h localhost -U core_app -d core_db -At \
  -c "SELECT id FROM core_test.jobs WHERE status='IN_PRODUCTION' ORDER BY random() LIMIT 20000" > jobs.txt

# app against the perf data, background jobs off so they do not skew the numbers
java -jar core-service/target/core-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local \
  "--spring.datasource.url=jdbc:postgresql://localhost:5432/core_db?currentSchema=core_test" \
  --spring.flyway.schemas=core_test --loofie.timers.enabled=false --loofie.outbox.relay.enabled=false \
  --loofie.retention.enabled=false

java scripts/perf/LoadTest.java <rps> <seconds> jobs.txt [listShare, default 0.25]
```

**Caveat:** on a laptop the app, the database and the load generator share one machine. Results are a lower
bound for the engine and not a capacity number for production; the real test runs on production-like hosting.

**Gotcha found while writing this:** after a bulk load, run `ANALYZE` before inserting rows with foreign keys
into it. Otherwise the foreign-key checks are planned as if the table were empty and every insert scans it
(the first attempt took over 20 minutes for 1M rows instead of about 1).

F6 actions (each job can be shipped and received once, so take fresh `CREATOR_ACCEPTED` jobs per run):

```bash
PGPASSWORD=core_local "$PSQL" -h localhost -U core_app -d core_db -At -F, -c "SELECT j.id, j.brand_org_id, a.creator_id
  FROM core_test.jobs j JOIN core_test.assignments a ON a.job_id = j.id AND a.status = 'ACTIVE'
  WHERE j.status = 'CREATOR_ACCEPTED' AND NOT j.digital_only ORDER BY random() LIMIT 6000" > f6jobs.txt
java scripts/perf/F6LoadTest.java <rps> <jobsPerPhase> f6jobs.txt
```

Outbox relay throughput (fake Backend A at 20 ms per request; only the relay runs):

```bash
java scripts/perf/FakeAiServer.java 8100 20 &
PGPASSWORD=core_local "$PSQL" -h localhost -U core_app -d core_db -f scripts/perf/relay-backlog.sql
PGPASSWORD=core_local "$PSQL" -h localhost -U core_app -d core_db -c "DELETE FROM core_test.scheduler_leases" # after a hard kill
AI_FEEDBACK_URL=http://127.0.0.1:8100 AI_FEEDBACK_API_KEY=perf java -jar core-service/target/core-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local "--spring.datasource.url=jdbc:postgresql://localhost:5432/core_db?currentSchema=core_test" \
  --spring.flyway.schemas=core_test --loofie.timers.enabled=false --loofie.retention.enabled=false
# watch: SELECT count(*) FROM core_test.outbox WHERE published_at IS NULL AND parked_at IS NULL;
```

Measured 2026-11-15: 1,145 events/s with the defaults (32 in flight, batch 200). Throughput = in-flight / latency.
Warm the app up (a short low-rate run) before a gated load test: the first seconds after boot are slow.

Refund requests and approvals (an OPS_LEAD asks and approves PAID jobs: nobody accepted, under Rs 10,000):

```bash
PGPASSWORD=core_local "$PSQL" -h localhost -U core_app -d core_db -At -c "SELECT id FROM core_test.jobs WHERE status='PAID' ORDER BY random() LIMIT 3000" > paid.txt
java scripts/perf/RefundLoadTest.java 50 3000 paid.txt
```

Measured 2026-11-16: request p95 5 ms, approve p95 6 ms at 50 rps; engine at 500 rps unchanged (p95 2 ms).
