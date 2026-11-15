# Performance scripts

Measure before optimising. These load a realistic data set and measure the hot queries and the API.

| File | What it does |
|---|---|
| `load-data.sql` | Fills the **test schema** `core_test` with 1M jobs (250k orders, 2,000 brands), 1M assignments, 2M events, 2M outbox rows, 400k notices. About 10 minutes. The next `mvn test` wipes it. |
| `explain.sql` | `EXPLAIN ANALYZE` of every hot query: engine, timers, relay, ops list, job detail, retention. |
| `LoadTest.java` | Open-loop HTTP load (fixed request rate, so a slow server shows up as latency). Mix: job detail, job list, transitions. Exits 1 unless p95 <= 300 ms and 0 failed (the gate). |
| `F6LoadTest.java` | F6 actions under load: brands mark jobs shipped, then creators confirm receipt with a photo. Same gate. |

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
