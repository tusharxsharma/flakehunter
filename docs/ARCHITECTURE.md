# Architecture & design decisions

## Goals

- **Accept reports from any CI system** with a single HTTP call and no agent to install.
- **Tell flaky tests apart from broken ones**, since they need different responses (quarantine vs. fix now).
- **Stay correct under retries and concurrency**: CI systems retry uploads, and many builds finish at once.
- **Degrade gracefully**: the cache, the event pipeline and the LLM are optional accelerators, never
  single points of failure.

## Components

| Service | Owns | Talks to |
|---|---|---|
| `api-service` (Java) | projects, runs, test cases, results, quarantine, outbox | PostgreSQL, Redis, Kafka (produce) |
| `triage-service` (Python) | failure clusters, processed events | its own PostgreSQL database, Kafka (consume), Claude (optional) |
| `dashboard` (React) | nothing, it's static files | both services through nginx |

Each service owns its data. The triage service never reads the API's tables; it learns about runs
only through events. That keeps the two deployable and scalable independently.

## Data model (api-service)

```
projects 1──* test_runs 1──* test_results *──1 test_cases *──1 projects
                                                   │
                                          quarantined, reason
outbox_events (event_id, payload, published_at)
```

- `test_cases.test_key = "<suite>::<name>"` identifies a test across runs; unique per project.
- `test_runs (project_id, build_id)` has a **partial unique index** (where `build_id` is not null):
  the database itself guarantees idempotent uploads.
- Indexes follow the queries: `(project_id, id DESC)` for "latest N runs",
  `(test_case_id, run_id DESC)` for a test's history.
- Schema changes are **Flyway migrations**; Hibernate only *validates* the schema at startup
  (`ddl-auto: validate`), so a mismatch fails fast instead of corrupting data.

## Request flow: uploading a report

1. `RateLimitFilter`: token bucket per API key (or IP). Over the limit → `429` + `Retry-After`.
2. `ApiKeyAuthenticationFilter`: SHA-256 of the key, then an indexed lookup. Unknown key → `401`.
3. `RunController`: reads at most 5 MB (bounded read, so a huge body cannot exhaust memory).
4. `IngestionService`:
   1. If `buildId` was seen before, return the original run (`200`, `duplicate: true`).
   2. Parse the XML **outside** the transaction. It's CPU work and should not hold DB locks.
   3. In **one transaction**: insert the run, upsert test cases, batch-insert results, insert an
      outbox event.
   4. Evict the project's analysis from the cache.
5. `OutboxPublisher` (every second): locks unpublished events with `FOR UPDATE SKIP LOCKED`, sends
   them to Kafka, and marks them published.

## Key decisions

### Streaming, hardened XML parsing
StAX (streaming) instead of DOM: memory stays constant regardless of report size. DTDs and
external entities are disabled, and any `DOCTYPE` is rejected. That blocks **XXE** (reading
server files through an XML entity) and **billion-laughs** entity-expansion attacks. Reports come
from CI jobs that may run untrusted pull-request code, so they must be treated as hostile.

### Transactional outbox instead of "save, then publish"
Writing to PostgreSQL and publishing to Kafka cannot be one atomic operation. If the API published
directly after committing, a crash in between would lose the event; publishing before committing
could announce a run that was then rolled back. The outbox row is committed **with** the run, and a
relay publishes it afterwards. The result is at-least-once delivery, so consumers deduplicate by
`eventId`. `FOR UPDATE SKIP LOCKED` lets several API replicas relay concurrently without sending
the same event twice. The Kafka key is the project id, so events for one project stay ordered on
one partition.

### Idempotency at every hop
| Hop | Mechanism |
|---|---|
| CI → API | unique `(project_id, build_id)`; a lost race returns the winner's run |
| API → Kafka | outbox + idempotent producer (`enable.idempotence=true`, `acks=all`) |
| Kafka → triage | `processed_events` row written in the same transaction as the cluster update; offsets committed only after that commit |

### Concurrency-safe test-case creation
Two builds of the same project can discover the same new test at the same moment. Test cases are
created with `INSERT … ON CONFLICT DO NOTHING` followed by a `SELECT`, so both succeed and see
the same id. There's no read-then-insert race.

### JDBC batching on the hot path, JPA elsewhere
A report can hold thousands of results. They are written with `JdbcTemplate.batchUpdate` (a few
round trips) rather than one JPA `persist` per row. JPA is used where it helps readability
(projects, quarantine), and plain SQL where performance and query plans matter (analytics).

### Caching that can never break the API
Analysis is cached in Redis per project (JSON, 10-minute TTL) and evicted on every upload and
quarantine change, so reads are fast and never stale. A `CacheErrorHandler` turns Redis failures
into cache misses. If Redis is down the API gets slower, not broken.

### API keys: random + SHA-256
Keys have 256 bits of entropy, so a fast hash is enough. Slow password hashes such as bcrypt
protect *low*-entropy human passwords. A deterministic hash also allows an indexed lookup. Only the
hash is stored, and the key is shown once.

### Flakiness model
See the README. Design properties, enforced by property-based tests:
- Score is always in `[0, 1]`; a never-failing test always scores 0.
- Skipped runs carry no information and never change the result.
- Renaming commits consistently never changes the result (only *equality* of commits matters).
- Any same-commit pass+fail yields `FLAKY` or `BROKEN`.
- O(n) time per test; the project analysis is O(window × tests).

Known limitation: a test that fails deterministically on some *environments* (for example one OS
only) looks flaky if all environments upload into one project. Use one project per environment,
or extend the model with an environment dimension.

### LLM triage with guardrails
Only the **first** failure of a new cluster is sent to Claude, so cost grows with the number of
distinct root causes, not with the number of failures. The response is constrained to a JSON
schema and validated with Pydantic. The failure text is wrapped in tags and declared untrusted in
the system prompt (a defence against prompt injection from test output). Any API error, refusal,
truncation or invalid output falls back to deterministic rules, so triage never blocks on the LLM.

## Scaling

| Pressure | Response |
|---|---|
| More uploads | API is stateless: add replicas (HPA on CPU). Outbox relay is replica-safe. |
| Bigger reports | Streaming parser + JDBC batches; the 5 MB limit is configurable. |
| More reads | Redis cache; `runs` and `results` indexes match the queries. |
| More events | Add triage replicas up to the partition count (3); raise partitions if needed. |
| Very large history | Partition `test_results` by month, or roll up old runs into daily aggregates. |
| Rate limiting across replicas | Move the token bucket to Redis (atomic Lua script); the algorithm is unchanged. |

## Observability

- `/actuator/health/{liveness,readiness}` are wired to Kubernetes probes.
- `/actuator/prometheus` exposes JVM and HTTP metrics plus custom counters
  (`flakehunter_ingestion_runs_total`, `flakehunter_outbox_pending`, …).
- The triage service exposes `/metrics` (events processed, rejected, duplicate; clusters created
  by classifier).
