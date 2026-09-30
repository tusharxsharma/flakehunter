"""Core use case: turn a TestRunIngested event into updated failure clusters."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

from prometheus_client import Counter
from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from .classifier import FailureClassifier, FailureSample
from .db import ClusterTest, FailureCluster, ProcessedEvent, utcnow
from .fingerprint import fingerprint, signature

log = logging.getLogger(__name__)

EVENTS_PROCESSED = Counter("triage_events_processed_total", "TestRunIngested events processed")
EVENTS_DUPLICATE = Counter("triage_events_duplicate_total", "Events skipped because they were already processed")
CLUSTERS_CREATED = Counter("triage_clusters_created_total", "New failure clusters", ["classified_by"])


class InvalidEventError(ValueError):
    """The event does not match the TestRunIngested contract."""


@dataclass(frozen=True)
class ProcessingResult:
    duplicate: bool
    failures_seen: int = 0
    clusters_created: int = 0


class TriageService:
    def __init__(self, sessions: sessionmaker, classifier: FailureClassifier) -> None:
        self._sessions = sessions
        self._classifier = classifier

    def handle_event(self, event: dict[str, Any]) -> ProcessingResult:
        event_id, project_id, failures = _validate(event)

        with self._sessions.begin() as session:
            if session.get(ProcessedEvent, event_id) is not None:
                EVENTS_DUPLICATE.inc()
                return ProcessingResult(duplicate=True)

            created = 0
            for failure in failures:
                if self._record_failure(session, project_id, failure):
                    created += 1
            # Marking the event processed in the SAME transaction as the cluster updates means a crash
            # can never leave the event half-applied: it is either fully counted or will be redelivered.
            session.add(ProcessedEvent(event_id=event_id))

        EVENTS_PROCESSED.inc()
        log.info("Processed event %s: %d failures, %d new clusters", event_id, len(failures), created)
        return ProcessingResult(duplicate=False, failures_seen=len(failures), clusters_created=created)

    def _record_failure(self, session: Session, project_id: int, failure: dict[str, Any]) -> bool:
        message = failure.get("message")
        test_key = str(failure.get("testKey") or "unknown")
        fp = fingerprint(message)

        cluster = session.scalars(
            select(FailureCluster).where(FailureCluster.project_id == project_id, FailureCluster.fingerprint == fp)
        ).one_or_none()

        created = cluster is None
        if cluster is None:
            # Only a brand-new root cause is classified; repeats just increment counters.
            verdict = self._classifier.classify(FailureSample(test_key=test_key, message=message))
            cluster = FailureCluster(
                project_id=project_id,
                fingerprint=fp,
                signature=signature(message),
                category=verdict.category.value,
                root_cause=verdict.root_cause,
                likely_flaky=verdict.likely_flaky,
                suggested_action=verdict.suggested_action,
                classified_by=verdict.source,
                sample_message=(message or "")[:4000] or None,
                occurrences=0,
                retry_passes=0,
            )
            session.add(cluster)
            session.flush()
            CLUSTERS_CREATED.labels(classified_by=verdict.source).inc()

        cluster.occurrences += 1
        if failure.get("passedOnRetry"):
            cluster.retry_passes += 1
        cluster.last_seen = utcnow()

        link = session.scalars(
            select(ClusterTest).where(ClusterTest.cluster_id == cluster.id, ClusterTest.test_key == test_key)
        ).one_or_none()
        if link is None:
            link = ClusterTest(cluster_id=cluster.id, test_key=test_key, occurrences=0)
            session.add(link)
        link.occurrences += 1
        return created


def _validate(event: dict[str, Any]) -> tuple[str, int, list[dict[str, Any]]]:
    if not isinstance(event, dict) or event.get("eventType") != "TestRunIngested":
        raise InvalidEventError("not a TestRunIngested event")
    event_id = event.get("eventId")
    project_id = event.get("projectId")
    failures = event.get("failures")
    if not isinstance(event_id, str) or not event_id:
        raise InvalidEventError("eventId is required")
    if not isinstance(project_id, int):
        raise InvalidEventError("projectId must be an integer")
    if not isinstance(failures, list) or not all(isinstance(f, dict) for f in failures):
        raise InvalidEventError("failures must be a list of objects")
    return event_id, project_id, failures
