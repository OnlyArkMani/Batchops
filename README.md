# BatchOPS

BatchOPS is an event-driven batch processing platform for school data: departments upload CSV / Excel / PDF result
files, a Kafka pipeline standardizes, validates, transforms and publishes them, and failures become deduplicated
incidents with safe auto-remediation, tickets, and a live dashboard.

This is the **Spring Boot rewrite of the original Django app (Flowboard)**. The React frontend is copied unchanged and the
REST API is wire-compatible (same paths, snake_case JSON, DRF-style pagination, `Authorization: Token <key>`), so the UI
works against the new backend with no code changes. The backend adds the reliability and scalability upgrades described below.

```
React (Vite) ──REST/SSE──▶ API (Spring Boot) ──▶ PostgreSQL (state + outbox + idempotency)
                               │                      ▲
                               └── outbox relay ──▶ Kafka ──▶ stage consumers ──▶ MinIO / S3 (immutable artifacts)
                                                       │
                                     retry.5s/1m/10m ◀─┴─▶ DLQ ──▶ replay
```

## Quick start

```bash
cp .env.example .env                       # optional: set BATCHOPS_ADMIN_PASSWORD
docker compose up --build                  # db, kafka (KRaft), minio, mailhog, api, worker
cd frontend && npm install && npm run dev  # http://localhost:5173  (VITE_API_BASE_URL=http://localhost:8000)
```

* First admin: username `admin`; the password is `BATCHOPS_ADMIN_PASSWORD`, or a random one printed **once** in the `api` logs.
* Mail (verification / reset codes) lands in MailHog: http://localhost:8025. MinIO console: http://localhost:9001.
* Scale workers independently: `docker compose up -d --scale worker=3` (Kafka partitions keyed by `upload_id` spread the load).
* Metrics: `docker compose --profile observability up` then http://localhost:9090.

Run backend tests / build locally (Java 21, Maven):

```bash
cd backend && mvn verify
```

Without Docker: start Postgres and Kafka, then `STORAGE_TYPE=local mvn spring-boot:run` (defaults: Postgres `localhost:5432`, Kafka `localhost:29092`).

## What changed, and why (interview talking points)

| Problem in the original | Upgrade |
|---|---|
| Redis/RQ queue: jobs lost on flush, no ordering, one monolithic 300-line task | **Kafka pipeline** with four independent stage consumers, topics keyed by `upload_id` (per-upload ordering, parallelism across uploads, replayable log) |
| DB write and enqueue could diverge | **Transactional outbox**: state change + event commit atomically; a relay (`FOR UPDATE SKIP LOCKED`) publishes to Kafka, safe with multiple replicas |
| Retries duplicated work / double-processed | **At-least-once + idempotent consumers**: `processed_events(consumer, event_id)` inserted in the same transaction as side effects; stale messages skipped by upload `version` |
| Ad-hoc, inconsistent retry configs | **One retry policy**: transient failures walk `uploads.retry.5s → 1m → 10m` (non-blocking delay topics), permanent failures go straight to an incident, exhausted transient failures land in the **DLQ** with an admin **replay** endpoint |
| Validation was mostly absent; known-error regexes could never match real messages | Real **schema validation** with row-level evidence (`validation_report`), and a **known-error catalogue whose patterns are unit-tested for reachability** against every validator message |
| Auto-fix silently rewrote data and overwrote the source file | **Immutable source + versioned artifacts** in S3/MinIO. Fixes are split into *safe* (run automatically) and *destructive* (dedupe, clip scores, drop rows) which wait for a moderator via `POST /api/incidents/{id}/approve-fixes` unless explicitly enabled |
| Every retry created another incident | **Incident fingerprinting** (`sha256(upload, step, normalized error)`) with a partial unique index: repeats bump `occurrence_count`; bounded auto/manual retries; scoped auto-resolve after success |
| Cron registered only on model save (lost with Redis); `callable` executed any dotted path | **DB-reconciling scheduler** (reads `jobs` every tick, catches up missed fires, dedupes replicas via a unique `job_fires` insert) and a **task registry allow-list** (no arbitrary code execution) |
| Runs stuck in `running` forever | **Reaper** fails them and raises an incident |
| Tokens stored in clear, never expired; 6-digit codes brute-forceable; everyone saw everything | SHA-256-hashed, **expiring tokens**; BCrypt; **login throttling**; reset/verify codes hashed with an **attempt limit**; optional **per-department scoping**; decoy responses for unknown users on forgot/verify |
| Polling for status | **SSE** live feed (`GET /api/events/stream?token=…`) fed from the `uploads.status` topic |
| In-process counters | **Micrometer + Prometheus** (`/api/metrics`, `/actuator/prometheus`), outbox-backlog and DLQ gauges, stage timers, optional OpenTelemetry tracing (`TRACING_ENABLED=true`) |

### Design notes worth being able to explain

* **Why the outbox?** Kafka and Postgres cannot share a transaction. Writing the event to an `outbox` table inside the
  business transaction, then relaying it, turns a dual write into one atomic write plus at-least-once delivery.
* **Exactly-once effect, not exactly-once delivery.** Duplicates are possible (relay crash after send, consumer rebalance);
  the idempotency table makes them harmless.
* **Why retry topics instead of `sleep`?** A blocking retry stalls the partition, so one bad file delays every upload behind it.
  Delay tiers isolate slow retries; the tier consumer `nack`s with a sleep until the message's `not-before` time.
* **Ordering.** Key = `upload_id` ⇒ one upload's messages stay ordered on one partition; throughput scales with partitions.
* **Pure domain core.** Validation, auto-fix, retry policy, state machines, and summary math are framework-free Java in
  `com.batchops.domain` and are unit-tested without Spring.

## API compatibility notes

Identical to the Django app: `/api/auth/*`, `/api/uploads/`, `/api/job-runs/`, `/api/jobs/`, `/api/incidents/`,
`/api/tickets/`, `/api/reports/summary`, `/api/health/`, `/api/metrics`, `/api/dashboard-metrics`, with trailing-slash tolerance,
`?ordering=`, `?page=` / `?page_size=` (default and max 5000).

Intentional differences:

* `report_csv` / `report_pdf` on uploads are empty strings; the UI already falls back to `/api/reports/summary` (served from object storage).
* Uploading a byte-identical file to the same department returns the existing upload (HTTP 200) instead of processing it twice.
* `POST /api/jobs/` requires `config.task` (a registered task name) instead of a dotted Python path.
* Additive endpoints: `/api/admin/users`, `/api/known-errors`, `/api/admin/dead-letters[/…/replay]`, `/api/incidents/{id}/approve-fixes`, `/api/events/stream`.
* Ticket/incident actor names now come from the authenticated user instead of the literal string `engine`.

## Configuration

See `backend/src/main/resources/application.yml`; everything is overridable with environment variables
(`DB_*`, `KAFKA_BOOTSTRAP`, `STORAGE_TYPE=local|s3`, `S3_*`, `EMAIL_*`, `BATCHOPS_*`). `BATCHOPS_ROLES_API/WORKER/SCHEDULER`
control what each process does so the API and workers deploy and scale separately.

## Repository layout

```
frontend/   React + Vite app (unchanged from the Django version)
backend/    Spring Boot 3 / Java 21
  domain/       framework-free core: validation, auto-fix, retry policy, state machines (unit-tested)
  persistence/  JPA entities + Spring Data repositories (Flyway migrations in resources/db/migration)
  events/       outbox, relay, stage + retry listeners, DLQ, idempotency guard
  pipeline/     stage processors, upload ingestion, job-run tracking
  incident/     incident engine (dedupe, triage, remediation), incident/ticket services
  schedule/     scheduler, task registry, tasks, stuck-run reaper
  security/     token auth, login throttle, department scoping, auth flows
  api/          REST controllers with DRF-compatible shapes
  io/ storage/  CSV/XLSX/PDF loaders, report writers, S3/MinIO + local object stores
```
