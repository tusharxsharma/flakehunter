# Test strategy

FlakeHunter is a tool about test reliability, so its own tests have to be fast, deterministic and
meaningful. This document explains what is tested where, and why.

## The pyramid

```
                 ┌──────────────┐
                 │   k6 load    │  budgets: p95 upload < 500 ms, read < 300 ms
               ┌─┴──────────────┴─┐
               │ Playwright E2E   │  15 · real browser, full docker stack
             ┌─┴──────────────────┴─┐
             │ Cucumber + REST      │  24 scenarios · black-box API + JSON Schema contracts
             │ Assured acceptance   │
           ┌─┴──────────────────────┴─┐
           │ Integration (Spring Boot, │  61 · real PostgreSQL + Kafka, no mocks
           │ embedded PG & Kafka)      │
         ┌─┴───────────────────────────┴─┐
         │ Unit + property-based          │  73 Java + 74 Python + 41 React
         │ (JUnit 6, jqwik, pytest, Vitest)│
         └────────────────────────────────┘
```

Most of the confidence comes from the fast layers. The slow layers check what only they can check:
real browsers, real networking between containers, real Kafka.

## What each layer is responsible for

| Layer | Owns | Deliberately does NOT test |
|---|---|---|
| Unit | Algorithms and edge cases: XML dialects, time parsing, scoring math, token-bucket timing, outbox ordering, fingerprint normalisation, classifier fallbacks | Framework wiring |
| Property-based (jqwik) | Invariants of the flakiness model over thousands of generated histories | Specific numeric examples (unit tests do that) |
| Integration (`*IT`) | SQL correctness, transactions, idempotency, security rules, HTTP status codes and error bodies, cache eviction, Kafka publishing | Browser behaviour |
| Acceptance (Cucumber) | Business rules in the domain's language, API contracts via JSON Schema, auth boundaries, against a *deployed* stack | Internal implementation |
| E2E (Playwright) | User journeys, rendering, XSS safety, accessibility, behaviour when a backend is down | Business-rule permutations (too slow here; covered below) |
| Performance (k6) | Latency and error-rate budgets under concurrent load | Functional correctness |

## Techniques worth calling out

- **Real dependencies over mocks.** Integration tests run against real PostgreSQL (embedded
  binaries) and an embedded Kafka broker, because the code relies on PostgreSQL-specific SQL
  (`ON CONFLICT`, `SKIP LOCKED`, partial indexes). An H2 imitation would give false confidence.
  They need no Docker, so they run on any laptop.
- **Property-based testing** (jqwik) for the scoring model, e.g. "inserting a skipped run anywhere
  never changes the verdict". When a property fails, jqwik shrinks the input to the smallest
  failing history.
- **Deterministic time.** The rate limiter takes an injectable clock; tests move time forward
  instead of sleeping.
- **Test data isolation.** Acceptance and E2E tests create uniquely named projects per
  scenario/test, so they can run in parallel and repeatedly against a shared environment without
  cleanup. Cucumber runs 4 scenarios in parallel.
- **API-first test setup.** Playwright seeds data through the REST API, not the UI. Setup takes
  milliseconds, and each test exercises only the UI behaviour it is named after.
- **Page Object Model.** Playwright specs talk to `DashboardPage`; selectors live in one place and
  prefer `data-testid` and ARIA roles over CSS classes.
- **Network mocking** (`page.route`) to simulate outages: API down → error banner; triage service
  down → flakiness data still shown.
- **Contract testing.** Responses are validated against JSON Schemas with
  `additionalProperties: false`, so an accidentally leaked field (for example the API key in a
  list response) fails the build.
- **Security tests at every layer.** XXE payload (unit, integration, acceptance), missing or
  invalid API keys, cross-project access (403), hostile test names rendered in the browser (E2E),
  prompt-injection handling (triage unit tests).
- **Accessibility.** axe-core checks WCAG 2.1 AA on the dashboard and the detail panel.
- **LLM without network.** The Claude classifier is tested with a fake client: request shape,
  schema enforcement, and fallback on rate-limit, connection error, server error, refusal,
  truncation and invalid JSON. There's no cost and no flakiness.

## Flakiness policy for our own tests

- CI retries a failed test **once** (Surefire `rerunFailingTestsCount`, Playwright `retries`).
  A test that passes on retry is reported as flaky, not silently passed.
- **Dogfooding:** CI uploads the JUnit output of every suite (Surefire, Failsafe, pytest, Vitest,
  Cucumber, Playwright) to the running FlakeHunter and prints any flaky tests. This also proves
  the parser handles six real-world report producers.
- New E2E tests are validated with `npx playwright test --repeat-each=3` before merging.

## Quality gates (the build fails if any is violated)

| Gate | Threshold |
|---|---|
| Java line coverage (JaCoCo, unit + integration) | ≥ 85% |
| Python coverage (pytest-cov) | ≥ 90% |
| Dashboard line coverage (Vitest v8) | ≥ 80% |
| Lint / format | Ruff, oxlint, TypeScript `strict` |
| Performance (k6) | p95 upload < 500 ms, p99 < 1 s, p95 read < 300 ms, errors < 1% |
| Static security analysis | CodeQL (Java, Python, TypeScript) |
| Kubernetes manifests | kubeconform strict schema validation |

## Running the suites

```bash
# Java unit + integration (Java 21 only, no Docker needed)
cd services/api-service && ./mvnw verify

# Python
cd services/triage-service && pip install -e ".[dev]" && pytest

# Dashboard
cd dashboard && npm ci && npm test

# Everything below needs the stack running: docker compose up -d --build --wait
cd qa/api-tests && ./mvnw test                                  # FLAKEHUNTER_API_URL=http://localhost:8080
cd qa/e2e-tests && npm ci && npx playwright install chromium && BASE_URL=http://localhost:3000 npx playwright test
k6 run qa/performance/load-test.js
python scripts/verify_event_pipeline.py
```
