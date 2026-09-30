"""HTTP API (FastAPI). Interactive docs are served at /docs."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import datetime

import anthropic
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select, text
from sqlalchemy.orm import selectinload, sessionmaker

from . import __version__
from .classifier import ClaudeClassifier, FailureClassifier, FailureSample, HeuristicClassifier
from .config import Settings
from .consumer import TriageConsumer, create_kafka_consumer
from .db import FailureCluster, create_engine_and_schema, session_factory
from .fingerprint import fingerprint, signature
from .service import TriageService

log = logging.getLogger(__name__)


class ClusterTestOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    test_key: str
    occurrences: int


class ClusterOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    project_id: int
    fingerprint: str
    signature: str
    category: str
    root_cause: str
    likely_flaky: bool
    suggested_action: str
    classified_by: str
    occurrences: int
    retry_passes: int
    first_seen: datetime
    last_seen: datetime
    sample_message: str | None
    tests: list[ClusterTestOut]


class PreviewRequest(BaseModel):
    test_key: str = Field(default="adhoc::preview", max_length=1024)
    message: str = Field(min_length=1, max_length=20_000)


class PreviewOut(BaseModel):
    fingerprint: str
    signature: str
    category: str
    root_cause: str
    likely_flaky: bool
    suggested_action: str
    classified_by: str


def build_classifier(settings: Settings) -> FailureClassifier:
    if settings.llm_enabled:
        log.info("LLM triage enabled with model %s", settings.anthropic_model)
        return ClaudeClassifier(anthropic.Anthropic(), settings.anthropic_model)
    log.info("LLM triage disabled (no ANTHROPIC_API_KEY); using heuristic classification")
    return HeuristicClassifier()


def create_app(
    settings: Settings | None = None,
    classifier: FailureClassifier | None = None,
    start_consumer: bool = True,
) -> FastAPI:
    settings = settings or Settings.from_env()
    engine = create_engine_and_schema(settings.database_url)
    sessions: sessionmaker = session_factory(engine)
    classifier = classifier or build_classifier(settings)
    service = TriageService(sessions, classifier)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        consumer = None
        if start_consumer and settings.kafka_bootstrap_servers:
            consumer = TriageConsumer(
                create_kafka_consumer(settings.kafka_bootstrap_servers, settings.kafka_group_id),
                settings.kafka_topic,
                service,
            )
            consumer.start()
            log.info("Consuming %s from %s", settings.kafka_topic, settings.kafka_bootstrap_servers)
        yield
        if consumer is not None:
            consumer.stop()
        engine.dispose()

    app = FastAPI(
        title="FlakeHunter Triage API",
        version=__version__,
        description="Failure clusters and root-cause hypotheses for FlakeHunter projects.",
        lifespan=lifespan,
    )
    app.state.service = service

    @app.get("/health", tags=["ops"])
    def health() -> dict[str, str]:
        with sessions() as session:
            session.execute(text("SELECT 1"))
        return {"status": "UP", "llm": "enabled" if settings.llm_enabled else "disabled"}

    @app.get("/metrics", tags=["ops"], response_class=PlainTextResponse)
    def metrics() -> PlainTextResponse:
        return PlainTextResponse(generate_latest().decode("utf-8"), media_type=CONTENT_TYPE_LATEST)

    @app.get("/api/v1/projects/{project_id}/clusters", response_model=list[ClusterOut], tags=["clusters"])
    def list_clusters(
        project_id: int,
        limit: int = Query(default=20, ge=1, le=100),
        flaky_only: bool = False,
    ) -> list[FailureCluster]:
        query = (
            select(FailureCluster)
            .options(selectinload(FailureCluster.tests))
            .where(FailureCluster.project_id == project_id)
            .order_by(FailureCluster.occurrences.desc(), FailureCluster.id)
            .limit(limit)
        )
        if flaky_only:
            query = query.where(FailureCluster.likely_flaky.is_(True))
        with sessions() as session:
            return list(session.scalars(query))

    @app.get("/api/v1/projects/{project_id}/clusters/summary", tags=["clusters"])
    def cluster_summary(project_id: int) -> dict[str, int]:
        with sessions() as session:
            rows = session.execute(
                select(FailureCluster.category, func.sum(FailureCluster.occurrences))
                .where(FailureCluster.project_id == project_id)
                .group_by(FailureCluster.category)
            ).all()
        return {category: int(total) for category, total in rows}

    @app.get("/api/v1/clusters/{cluster_id}", response_model=ClusterOut, tags=["clusters"])
    def get_cluster(cluster_id: int) -> FailureCluster:
        with sessions() as session:
            cluster = session.scalars(
                select(FailureCluster)
                .options(selectinload(FailureCluster.tests))
                .where(FailureCluster.id == cluster_id)
            ).one_or_none()
        if cluster is None:
            raise HTTPException(status_code=404, detail=f"Cluster {cluster_id} not found")
        return cluster

    @app.post("/api/v1/triage/preview", response_model=PreviewOut, tags=["triage"])
    def preview(request: PreviewRequest) -> PreviewOut:
        """Classifies an arbitrary failure message without storing anything."""
        verdict = classifier.classify(FailureSample(test_key=request.test_key, message=request.message))
        return PreviewOut(
            fingerprint=fingerprint(request.message),
            signature=signature(request.message),
            category=verdict.category.value,
            root_cause=verdict.root_cause,
            likely_flaky=verdict.likely_flaky,
            suggested_action=verdict.suggested_action,
            classified_by=verdict.source,
        )

    return app
