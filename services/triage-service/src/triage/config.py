"""Configuration from environment variables (12-factor style)."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    database_url: str = "sqlite:///./triage.db"
    kafka_bootstrap_servers: str | None = None
    kafka_topic: str = "flakehunter.test-runs.v1"
    kafka_group_id: str = "flakehunter-triage"
    anthropic_model: str = "claude-opus-5"
    llm_enabled: bool = False

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> Settings:
        env = dict(os.environ) if env is None else env
        return cls(
            database_url=env.get("TRIAGE_DATABASE_URL", cls.database_url),
            kafka_bootstrap_servers=env.get("KAFKA_BOOTSTRAP_SERVERS") or None,
            kafka_topic=env.get("KAFKA_TOPIC", cls.kafka_topic),
            kafka_group_id=env.get("KAFKA_GROUP_ID", cls.kafka_group_id),
            anthropic_model=env.get("TRIAGE_MODEL", cls.anthropic_model),
            # The LLM is opt-in: without an API key the service still works using heuristics.
            llm_enabled=bool(env.get("ANTHROPIC_API_KEY")) and env.get("TRIAGE_LLM_ENABLED", "true") == "true",
        )
