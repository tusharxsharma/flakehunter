from __future__ import annotations

import uuid
from typing import Any

import pytest
from fastapi.testclient import TestClient

from triage.api import create_app
from triage.classifier import HeuristicClassifier
from triage.config import Settings
from triage.db import create_engine_and_schema, session_factory
from triage.service import TriageService


@pytest.fixture
def sessions():
    engine = create_engine_and_schema("sqlite://")
    yield session_factory(engine)
    engine.dispose()


@pytest.fixture
def service(sessions) -> TriageService:
    return TriageService(sessions, HeuristicClassifier())


@pytest.fixture
def client() -> TestClient:
    app = create_app(Settings(database_url="sqlite://"), classifier=HeuristicClassifier(), start_consumer=False)
    with TestClient(app) as test_client:
        yield test_client


def make_event(project_id: int = 1, failures: list[dict[str, Any]] | None = None, **overrides: Any) -> dict[str, Any]:
    """A TestRunIngested event exactly as the Java API publishes it."""
    event = {
        "eventId": str(uuid.uuid4()),
        "eventType": "TestRunIngested",
        "occurredAt": "2026-09-26T10:00:00Z",
        "projectId": project_id,
        "projectName": "shop",
        "runId": 1,
        "commitSha": "abc1234",
        "branch": "main",
        "buildId": None,
        "total": 10,
        "passed": 10 - len(failures or []),
        "failed": len(failures or []),
        "skipped": 0,
        "failures": failures or [],
    }
    event.update(overrides)
    return event


def failure(test_key: str, message: str | None, passed_on_retry: bool = False) -> dict[str, Any]:
    suite, _, name = test_key.partition("::")
    return {
        "testId": abs(hash(test_key)) % 10_000,
        "testKey": test_key,
        "suite": suite,
        "name": name,
        "message": message,
        "passedOnRetry": passed_on_retry,
    }
