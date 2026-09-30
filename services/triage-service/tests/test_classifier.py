from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any

import anthropic
import httpx2
import pytest

from triage.classifier import Category, ClaudeClassifier, FailureSample, HeuristicClassifier

heuristic = HeuristicClassifier()


@pytest.mark.parametrize(
    ("message", "category", "flaky"),
    [
        ("java.util.concurrent.TimeoutException: Timed out after 500 ms", Category.TIMEOUT, True),
        ("TimeoutError: page.click: Timeout 30000ms exceeded", Category.TIMEOUT, True),
        ("Connection refused: localhost/127.0.0.1:5432", Category.INFRASTRUCTURE, True),
        ("HTTP 503 Service Unavailable from payment gateway", Category.INFRASTRUCTURE, True),
        ("NoSuchElementException: no such element: #checkout", Category.UI_ELEMENT, True),
        ("StaleElementReferenceException: stale element reference", Category.UI_ELEMENT, True),
        ("Deadlock found when trying to get lock", Category.CONCURRENCY, True),
        ("java.lang.NullPointerException at CartService.total", Category.NULL_REFERENCE, False),
        ("TypeError: Cannot read properties of undefined (reading 'id')", Category.NULL_REFERENCE, False),
        ("AssertionError: expected 90 but was 100", Category.ASSERTION, False),
        ("expected: <true> but was: <false>", Category.ASSERTION, False),
        ("Segmentation fault", Category.UNKNOWN, False),
    ],
)
def test_heuristic_rules(message: str, category: Category, flaky: bool) -> None:
    result = heuristic.classify(FailureSample("suite::test", message))

    assert result.category is category
    assert result.likely_flaky is flaky
    assert result.source == "heuristic"
    assert result.suggested_action


def test_heuristic_handles_missing_message() -> None:
    assert heuristic.classify(FailureSample("s::t", None)).category is Category.UNKNOWN


def test_timeout_wins_over_assertion_when_both_appear() -> None:
    result = heuristic.classify(FailureSample("s::t", "AssertionError: request timed out"))
    assert result.category is Category.TIMEOUT


# ---------- ClaudeClassifier with a fake Anthropic client (no network, no cost) ----------


class FakeMessages:
    def __init__(self, response: Any = None, error: Exception | None = None) -> None:
        self.response = response
        self.error = error
        self.calls: list[dict[str, Any]] = []

    def create(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return self.response


def fake_client(messages: FakeMessages) -> Any:
    return SimpleNamespace(beta=SimpleNamespace(messages=messages))


def llm_response(payload: dict[str, Any] | str, stop_reason: str = "end_turn") -> Any:
    text = payload if isinstance(payload, str) else json.dumps(payload)
    return SimpleNamespace(stop_reason=stop_reason, content=[SimpleNamespace(type="text", text=text)])


VALID = {
    "category": "CONCURRENCY",
    "root_cause": "Two tests share the same Redis key.",
    "likely_flaky": True,
    "suggested_action": "Namespace keys per test.",
}

SAMPLE = FailureSample("com.shop.CartTest::loadsCart", "expected 2 items but was 3")
_REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")


def test_claude_verdict_is_used_when_valid() -> None:
    messages = FakeMessages(llm_response(VALID))

    result = ClaudeClassifier(fake_client(messages), "claude-opus-5").classify(SAMPLE)

    assert result.category is Category.CONCURRENCY
    assert result.likely_flaky is True
    assert result.source == "llm"


def test_request_is_schema_constrained_and_treats_output_as_data() -> None:
    messages = FakeMessages(llm_response(VALID))

    ClaudeClassifier(fake_client(messages), "claude-opus-5").classify(SAMPLE)

    call = messages.calls[0]
    assert call["model"] == "claude-opus-5"
    assert call["output_config"]["format"]["type"] == "json_schema"
    assert call["output_config"]["format"]["schema"]["additionalProperties"] is False
    assert call["fallbacks"] == "default"
    assert "<failure_output>" in call["messages"][0]["content"]
    assert "untrusted" in call["system"]


def test_very_long_messages_are_capped() -> None:
    messages = FakeMessages(llm_response(VALID))

    ClaudeClassifier(fake_client(messages), "m").classify(FailureSample("s::t", "x" * 50_000))

    assert len(messages.calls[0]["messages"][0]["content"]) < 7_000


@pytest.mark.parametrize(
    "error",
    [
        anthropic.APIConnectionError(request=_REQUEST),
        anthropic.RateLimitError("slow down", response=httpx2.Response(429, request=_REQUEST), body=None),
        anthropic.InternalServerError("boom", response=httpx2.Response(500, request=_REQUEST), body=None),
    ],
    ids=["connection", "rate-limit", "server-error"],
)
def test_falls_back_to_heuristics_on_api_errors(error: Exception) -> None:
    result = ClaudeClassifier(fake_client(FakeMessages(error=error)), "m").classify(SAMPLE)

    assert result.source == "heuristic"
    assert result.category is Category.ASSERTION


@pytest.mark.parametrize("stop_reason", ["refusal", "max_tokens"])
def test_falls_back_when_claude_does_not_finish(stop_reason: str) -> None:
    result = ClaudeClassifier(fake_client(FakeMessages(llm_response(VALID, stop_reason))), "m").classify(SAMPLE)
    assert result.source == "heuristic"


def test_falls_back_on_invalid_json() -> None:
    bad = {**VALID, "category": "NOT_A_CATEGORY"}
    result = ClaudeClassifier(fake_client(FakeMessages(llm_response(bad))), "m").classify(SAMPLE)
    assert result.source == "heuristic"


def test_falls_back_when_no_text_block() -> None:
    response = SimpleNamespace(stop_reason="end_turn", content=[SimpleNamespace(type="thinking", text=None)])
    result = ClaudeClassifier(fake_client(FakeMessages(response)), "m").classify(SAMPLE)
    assert result.source == "heuristic"
