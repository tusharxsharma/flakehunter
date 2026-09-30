"""Persistence: SQLAlchemy 2.0 ORM. PostgreSQL in production, SQLite in unit tests."""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    create_engine,
)
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship, sessionmaker
from sqlalchemy.pool import StaticPool


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class FailureCluster(Base):
    """All failures in a project that share one fingerprint (i.e. one root cause)."""

    __tablename__ = "failure_clusters"
    __table_args__ = (UniqueConstraint("project_id", "fingerprint", name="uq_cluster_project_fingerprint"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    project_id: Mapped[int] = mapped_column(Integer, index=True)
    fingerprint: Mapped[str] = mapped_column(String(16))
    signature: Mapped[str] = mapped_column(Text)
    category: Mapped[str] = mapped_column(String(32))
    root_cause: Mapped[str] = mapped_column(Text)
    likely_flaky: Mapped[bool] = mapped_column(Boolean)
    suggested_action: Mapped[str] = mapped_column(Text)
    classified_by: Mapped[str] = mapped_column(String(16))
    sample_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    occurrences: Mapped[int] = mapped_column(Integer, default=0)
    retry_passes: Mapped[int] = mapped_column(Integer, default=0)
    first_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    tests: Mapped[list[ClusterTest]] = relationship(
        back_populates="cluster", cascade="all, delete-orphan", order_by="ClusterTest.occurrences.desc()"
    )


class ClusterTest(Base):
    """How often each test hit a given cluster."""

    __tablename__ = "cluster_tests"
    __table_args__ = (UniqueConstraint("cluster_id", "test_key", name="uq_cluster_test"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    cluster_id: Mapped[int] = mapped_column(ForeignKey("failure_clusters.id", ondelete="CASCADE"), index=True)
    test_key: Mapped[str] = mapped_column(String(1024))
    occurrences: Mapped[int] = mapped_column(Integer, default=0)

    cluster: Mapped[FailureCluster] = relationship(back_populates="tests")


class ProcessedEvent(Base):
    """Kafka delivers at-least-once. Remembering event ids makes processing idempotent."""

    __tablename__ = "processed_events"

    event_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


def create_engine_and_schema(database_url: str) -> Engine:
    if database_url in ("sqlite://", "sqlite:///:memory:"):
        # One shared connection, otherwise every pooled connection would see its own empty database.
        engine = create_engine(database_url, poolclass=StaticPool, connect_args={"check_same_thread": False})
    else:
        engine = create_engine(database_url, pool_pre_ping=True)
    # Tables are small and owned solely by this service. A larger service would use Alembic migrations.
    Base.metadata.create_all(engine)
    return engine


def session_factory(engine: Engine) -> sessionmaker:
    return sessionmaker(bind=engine, expire_on_commit=False)
