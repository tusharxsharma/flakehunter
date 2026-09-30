from __future__ import annotations

import pytest
from conftest import failure, make_event
from sqlalchemy import select

from triage.classifier import Category, Classification, FailureSample, HeuristicClassifier
from triage.db import ClusterTest, FailureCluster
from triage.service import InvalidEventError, TriageService


def clusters(sessions) -> list[FailureCluster]:
    with sessions() as s:
        return list(s.scalars(select(FailureCluster).order_by(FailureCluster.id)))


def test_groups_failures_with_the_same_root_cause(service, sessions) -> None:
    result = service.handle_event(
        make_event(
            failures=[
                failure("Cart::a", "Timed out after 500 ms"),
                failure("Cart::b", "Timed out after 900 ms"),
                failure("Cart::c", "expected 1 but was 2"),
            ]
        )
    )

    assert result.failures_seen == 3
    assert result.clusters_created == 2
    timeout, assertion = clusters(sessions)
    assert timeout.category == Category.TIMEOUT.value
    assert timeout.occurrences == 2
    assert assertion.category == Category.ASSERTION.value


def test_counts_accumulate_across_events_and_per_test(service, sessions) -> None:
    for _ in range(3):
        service.handle_event(make_event(failures=[failure("Cart::a", "Connection refused: db:5432")]))
    service.handle_event(make_event(failures=[failure("Tax::b", "Connection refused: db:5433")]))

    (cluster,) = clusters(sessions)
    assert cluster.occurrences == 4
    with sessions() as s:
        links = {t.test_key: t.occurrences for t in s.scalars(select(ClusterTest))}
    assert links == {"Cart::a": 3, "Tax::b": 1}


def test_redelivered_event_is_ignored(service, sessions) -> None:
    event = make_event(failures=[failure("Cart::a", "boom")])

    first = service.handle_event(event)
    second = service.handle_event(event)

    assert first.duplicate is False
    assert second.duplicate is True
    assert clusters(sessions)[0].occurrences == 1


def test_clusters_are_scoped_per_project(service, sessions) -> None:
    service.handle_event(make_event(project_id=1, failures=[failure("A::x", "boom")]))
    service.handle_event(make_event(project_id=2, failures=[failure("A::x", "boom")]))

    assert [c.project_id for c in clusters(sessions)] == [1, 2]


def test_tracks_failures_that_passed_on_retry(service, sessions) -> None:
    service.handle_event(make_event(failures=[failure("Search::find", "Timed out", passed_on_retry=True)]))

    assert clusters(sessions)[0].retry_passes == 1


def test_run_without_failures_is_still_marked_processed(service, sessions) -> None:
    event = make_event(failures=[])

    assert service.handle_event(event).failures_seen == 0
    assert service.handle_event(event).duplicate is True


class CountingClassifier:
    def __init__(self) -> None:
        self.calls = 0

    def classify(self, sample: FailureSample) -> Classification:
        self.calls += 1
        return Classification(Category.UNKNOWN, "x", False, "y", "llm")


def test_classifier_runs_once_per_new_cluster_only(sessions) -> None:
    classifier = CountingClassifier()
    service = TriageService(sessions, classifier)

    for _ in range(5):
        service.handle_event(make_event(failures=[failure("A::x", "same error 1"), failure("A::y", "same error 2")]))

    assert classifier.calls == 1, "repeats must not trigger (paid) LLM calls"


class ExplodingClassifier:
    def classify(self, sample: FailureSample) -> Classification:
        raise RuntimeError("database went away")


def test_failure_mid_event_rolls_back_everything(sessions) -> None:
    event = make_event(failures=[failure("A::x", "boom")])

    with pytest.raises(RuntimeError):
        TriageService(sessions, ExplodingClassifier()).handle_event(event)

    assert clusters(sessions) == []
    # Not marked processed, so a redelivery can still succeed later
    assert TriageService(sessions, HeuristicClassifier()).handle_event(event).duplicate is False


@pytest.mark.parametrize(
    "bad",
    [
        None,
        "not a dict",
        {"eventType": "SomethingElse"},
        make_event(eventId=""),
        make_event(projectId="1"),
        make_event(failures="nope"),
        make_event(failures=["not-an-object"]),
    ],
)
def test_rejects_events_that_break_the_contract(service, bad) -> None:
    with pytest.raises(InvalidEventError):
        service.handle_event(bad)
