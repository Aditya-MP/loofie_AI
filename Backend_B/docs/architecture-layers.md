# core-service layers

Every feature in `core-service` is split into the same layers. The rules marked **(enforced)** are ArchUnit
tests in `ArchitectureTest`: breaking one fails the build.

```
api  ──►  app  ──►  domain
           │
           ▼
         infra (SQL)        read (query-side records, used by infra and app)
```

| Layer | Package | Does | Must not |
|---|---|---|---|
| **api** | `<feature>.api`, `ops.api` | Map HTTP to a use case: parse, validate, call one app service, return its result | Touch the database or check permissions **(enforced: no infra, no JDBC)** |
| **app** | `<feature>.app`, `job.timer` | Use cases: transaction boundary, permission checks, call domain rules, save, record events | Write SQL **(enforced)** |
| **domain** | `<feature>.domain` | Business rules as plain Java: states, transitions, deadlines, working days | Know about Spring web, Kafka, JDBC, infra or api **(enforced)** |
| **infra** | `<feature>.infra` | Repositories and SQL; the only place that knows table and column names | Depend on app, api or timer **(enforced)** |
| **read** | `job.read` | Read-model records (what the ops console shows) | Contain logic |

Cross-feature rules:
- Another feature uses a feature's **app services**, never its repositories **(enforced for job and calendar)**.
- Only `JobTransitionService` changes a job's state **(enforced)**.
- Only the job app layer writes `job_events` **(enforced)**.
- Shared plumbing (`messaging`, `events`, `common`, `housekeeping`) may use JDBC directly; it has no business rules.

## Map of today's features

| Feature | api | app | domain | infra |
|---|---|---|---|---|
| Jobs (F5, F6, F9) | `job.api.JobTransitionController` | `JobTransitionService`, `JobIntakeService`, `JobQueryService` | `TransitionTable`, `Job`, `JobDeadlines`, `JobDeadline`, `Shipment` | `JobRepository`, `JobReadRepository`, `JobDeadlineRepository`, `DeadlineColumns` |
| Timers | (none: scheduled) | `job.timer.DeadlineWorker` + `TimerRules` | uses job domain | `JobDeadlineRepository` |
| Working-day calendar | `ops.api.OpsHolidayController` | `HolidayCalendar`, `HolidayAdminService` | `WorkingDays`, `Holiday` | `HolidayRepository` |
| Ops console (F10) | `ops.api.OpsJobController` | `JobQueryService` | | `JobReadRepository` |
| Orders (stub) | (none yet) | `OrderSubmissionService` | `Order` | `OrderRepository` |

## Database migrations

Flyway, forward-only (there is no undo). Deploys are rolling, so for a while **the old and the new app version
run against the same schema**. Every migration must therefore work with both.

**Expand, then contract:**
1. **Expand** (release N): only additive changes, such as a new nullable column, a new table or a new index. The old version ignores them.
2. **Migrate** (release N): the new code writes both shapes, and a backfill fills old rows in small batches.
3. **Contract** (release N+1 or later, once nothing runs N-1): drop or rename the old column, add `NOT NULL`.

**Rules:**
- Never rename or drop a column, or narrow a type, in the same release that stops using it.
- Indexes on big tables: `CREATE INDEX CONCURRENTLY` in its own migration with
  `-- flyway:executeInTransaction=false` (a plain `CREATE INDEX` locks writes for the whole build).
- A slow migration starts with `SET LOCAL statement_timeout = 0; SET LOCAL lock_timeout = '10s';`. The pool's
  10 s statement timeout otherwise applies, and a short lock timeout stops it from queueing behind live traffic.
- Rewrites of millions of rows go in batches from a job, not in one migration.
- **If a migration fails:** PostgreSQL rolls it back (DDL is transactional), the new version does not start,
  and old instances keep serving, because the change was expand-only. Fix forward with a new migration;
  never edit an applied one (Flyway checksums refuse it).
- Before a contract step: confirm no running instance still reads the old shape.

## Design template for a new feature or a deepening phase

Write this before code, one section per layer:

1. **API**: endpoints, request and response, error types, idempotency, who may call.
2. **App**: use cases, transaction boundaries, permission checks, which events each one records.
3. **Domain**: states, rules, invariants, what is calendar vs working time.
4. **Infra**: tables, columns, constraints, indexes, the queries and their expected volume.
5. **Events**: what is published, on which topic, what consumers may rely on.
6. **Operations**: metrics, alerts, failure behaviour (dependency down, duplicate request, two instances).
7. **Tests**: rule tests, race and retry tests, failure tests.
