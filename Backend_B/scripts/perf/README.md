# Performance scripts

Measure before optimising. These load a realistic data set and measure the hot queries and the API.

| File | What it does |
|---|---|
| `load-data.sql` | Fills the **test schema** `core_test` with 1M jobs (250k orders, 2,000 brands), 1M assignments, 2M events, 2M outbox rows, 400k notices. About 10 minutes. The next `mvn test` wipes it. |
| `explain.sql` | `EXPLAIN ANALYZE` of every hot query: engine, timers, relay, ops list, job detail, retention. |
| `LoadTest.java` | Open-loop HTTP load (fixed request rate, so a slow server shows up as latency). Mix: job detail, job list, transitions. |

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
