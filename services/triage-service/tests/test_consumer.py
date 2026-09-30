from __future__ import annotations

import json
import time
from typing import Any

from conftest import failure, make_event
from sqlalchemy import func, select

from triage.consumer import TriageConsumer, process_message
from triage.db import FailureCluster


class FakeMessage:
    def __init__(self, value: bytes | None, error: Any = None) -> None:
        self._value = value
        self._error = error

    def value(self) -> bytes | None:
        return self._value

    def error(self) -> Any:
        return self._error


def encode(event: dict[str, Any]) -> FakeMessage:
    return FakeMessage(json.dumps(event).encode("utf-8"))


class FakeConsumer:
    """In-memory stand-in for confluent_kafka.Consumer."""

    def __init__(self, messages: list[FakeMessage]) -> None:
        self.queue = list(messages)
        self.subscribed: list[str] = []
        self.committed: list[FakeMessage] = []
        self.closed = False

    def subscribe(self, topics: list[str]) -> None:
        self.subscribed = topics

    def poll(self, timeout: float) -> FakeMessage | None:
        if self.queue:
            return self.queue.pop(0)
        time.sleep(0.01)
        return None

    def commit(self, message: FakeMessage, asynchronous: bool = True) -> None:
        self.committed.append(message)

    def close(self) -> None:
        self.closed = True


def count_clusters(sessions) -> int:
    with sessions() as s:
        return s.scalar(select(func.count()).select_from(FailureCluster))


def test_valid_message_is_processed_and_committable(service, sessions) -> None:
    assert process_message(service, encode(make_event(failures=[failure("A::x", "boom")]))) is True
    assert count_clusters(sessions) == 1


def test_poison_pill_is_skipped_but_committed(service) -> None:
    assert process_message(service, FakeMessage(b"{not json")) is True
    assert process_message(service, FakeMessage(b"\xff\xfe")) is True
    assert process_message(service, encode({"eventType": "Unknown"})) is True


def test_broker_error_is_not_committed(service) -> None:
    assert process_message(service, FakeMessage(None, error="partition EOF")) is False


def wait_until(condition, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.02)
    raise AssertionError("condition not met in time")


def test_consumer_loop_commits_after_processing(service, sessions) -> None:
    messages = [encode(make_event(failures=[failure("A::x", f"Timed out after {ms} ms")])) for ms in (100, 200, 300)]
    fake = FakeConsumer(messages)
    consumer = TriageConsumer(fake, "flakehunter.test-runs.v1", service)

    consumer.start()
    wait_until(lambda: len(fake.committed) == 3)
    consumer.stop()

    assert fake.subscribed == ["flakehunter.test-runs.v1"]
    assert fake.closed is True
    assert count_clusters(sessions) == 1  # three timeouts, one root cause


class FlakyService:
    """Fails the first attempt, like a database that is briefly unavailable."""

    def __init__(self) -> None:
        self.attempts = 0

    def handle_event(self, event: dict[str, Any]) -> None:
        self.attempts += 1
        if self.attempts == 1:
            raise ConnectionError("db unavailable")


def test_transient_failure_is_not_committed() -> None:
    message = encode(make_event())
    fake = FakeConsumer([message])
    service = FlakyService()
    consumer = TriageConsumer(fake, "t", service)  # type: ignore[arg-type]

    consumer.start()
    wait_until(lambda: service.attempts >= 1)
    consumer.stop()

    assert fake.committed == []
