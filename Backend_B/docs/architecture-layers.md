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

## Design template for a new feature or a deepening phase

Write this before code, one section per layer:

1. **API**: endpoints, request and response, error types, idempotency, who may call.
2. **App**: use cases, transaction boundaries, permission checks, which events each one records.
3. **Domain**: states, rules, invariants, what is calendar vs working time.
4. **Infra**: tables, columns, constraints, indexes, the queries and their expected volume.
5. **Events**: what is published, on which topic, what consumers may rely on.
6. **Operations**: metrics, alerts, failure behaviour (dependency down, duplicate request, two instances).
7. **Tests**: rule tests, race and retry tests, failure tests.
