# Triage service (Python)

Consumes `TestRunIngested` events from Kafka, groups failures that share a root cause
("fingerprinting"), and explains each group with a category, a root-cause hypothesis and a
suggested next step.

| | |
|---|---|
| Language | Python 3.10+ (runs on 3.12 in Docker) |
| Web | FastAPI + Uvicorn |
| Messaging | confluent-kafka (manual offset commits, idempotent processing) |
| Storage | SQLAlchemy 2.0: PostgreSQL in production, SQLite in tests |
| AI | Anthropic Claude (optional), with structured JSON output and a regex fallback |

## How a failure is triaged

1. **Fingerprint.** Volatile tokens are replaced with placeholders: numbers, UUIDs, SHAs, paths
   and quoted values. `Timed out after 512 ms` and `Timed out after 498 ms` become the same
   signature, `timed out after <n> ms`.
2. **Cluster.** Failures are grouped per project by fingerprint, and occurrences are counted per test.
3. **Classify once.** Only a *new* cluster is classified. Repeats just increment counters, so
   LLM cost grows with the number of distinct root causes, not with the number of failures.
   - With `ANTHROPIC_API_KEY` set: Claude returns a JSON-schema-constrained verdict. The failure
     output is passed as untrusted data inside tags, so instructions inside a test log are
     never followed.
   - Otherwise, or if the API errors, refuses or rate-limits: deterministic regex rules.

## Run locally

```bash
python -m venv .venv
.venv/bin/pip install -e ".[dev]"        # Windows: .venv\Scripts\pip
.venv/bin/pytest                          # 74 tests, coverage gate 90%
TRIAGE_DATABASE_URL=sqlite:///./triage.db .venv/bin/python -m triage   # http://localhost:8000/docs
```

| Variable | Default | Purpose |
|---|---|---|
| `TRIAGE_DATABASE_URL` | `sqlite:///./triage.db` | SQLAlchemy URL |
| `KAFKA_BOOTSTRAP_SERVERS` | unset (consumer off) | Kafka brokers |
| `KAFKA_TOPIC` | `flakehunter.test-runs.v1` | Topic published by the API |
| `ANTHROPIC_API_KEY` | unset | Enables LLM classification |
| `TRIAGE_MODEL` | `claude-opus-5` | Claude model id |
