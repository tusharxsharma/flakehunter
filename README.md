# FlakeHunter

**Flaky test detection and test analytics for CI pipelines.** *(Work in progress)*

A *flaky test* passes and fails on the same code. Flaky tests waste CI time, train engineers to
ignore red builds, and hide real bugs. FlakeHunter will:

1. Accept **JUnit XML reports** from any CI system (Maven, Gradle, pytest, Jest, Playwright...).
2. Score every test with a **statistical flakiness model** over its recent history.
3. Separate **flaky** tests (random) from **broken** ones (consistently failing).
4. Let teams **quarantine** flaky tests so CI can skip them while they are fixed.
5. **Cluster failures by root cause** and explain them.

## Planned architecture

| Component | Stack |
|---|---|
| API service | Java 21, Spring Boot 4, PostgreSQL, Flyway, Redis, Kafka |
| Triage service | Python, FastAPI, Kafka consumer, Claude for root-cause hints |
| Dashboard | React + TypeScript |
| Test automation | JUnit, jqwik, Cucumber + REST Assured, Playwright, k6 |
| Delivery | Docker Compose, Kubernetes, GitHub Actions |

The design is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Progress

- [x] Design document
- [x] API service skeleton, database schema
- [ ] JUnit XML ingestion
- [ ] Flakiness scoring engine
- [ ] REST API
- [ ] Event pipeline and triage service
- [ ] Dashboard
- [ ] End-to-end, acceptance and performance test suites
- [ ] CI/CD and deployment

## License

[MIT](LICENSE)
