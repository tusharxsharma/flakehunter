from __future__ import annotations

from conftest import failure, make_event

from triage.config import Settings


def seed(client, project_id: int = 7) -> None:
    service = client.app.state.service
    service.handle_event(
        make_event(
            project_id=project_id,
            failures=[
                failure("Checkout::pay", "Connection refused: payments:8080"),
                failure("Checkout::refund", "Connection refused: payments:8081"),
                failure("Cart::total", "expected 90 but was 100"),
            ],
        )
    )
    service.handle_event(
        make_event(
            project_id=project_id,
            failures=[
                failure("Checkout::pay", "Connection refused: payments:8080"),
            ],
        )
    )


def test_health(client) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "llm": "disabled"}


def test_lists_clusters_most_frequent_first(client) -> None:
    seed(client)

    body = client.get("/api/v1/projects/7/clusters").json()

    assert [c["category"] for c in body] == ["INFRASTRUCTURE", "ASSERTION"]
    top = body[0]
    assert top["occurrences"] == 3
    assert top["likely_flaky"] is True
    assert top["classified_by"] == "heuristic"
    assert top["tests"][0] == {"test_key": "Checkout::pay", "occurrences": 2}


def test_flaky_only_filter_and_limit(client) -> None:
    seed(client)

    assert len(client.get("/api/v1/projects/7/clusters", params={"flaky_only": True}).json()) == 1
    assert len(client.get("/api/v1/projects/7/clusters", params={"limit": 1}).json()) == 1


def test_validates_query_parameters(client) -> None:
    assert client.get("/api/v1/projects/7/clusters", params={"limit": 0}).status_code == 422
    assert client.get("/api/v1/projects/7/clusters", params={"limit": 101}).status_code == 422


def test_other_projects_are_not_visible(client) -> None:
    seed(client, project_id=7)
    assert client.get("/api/v1/projects/8/clusters").json() == []


def test_summary_by_category(client) -> None:
    seed(client)
    assert client.get("/api/v1/projects/7/clusters/summary").json() == {"INFRASTRUCTURE": 3, "ASSERTION": 1}


def test_get_cluster_and_404(client) -> None:
    seed(client)
    cluster_id = client.get("/api/v1/projects/7/clusters").json()[0]["id"]

    assert client.get(f"/api/v1/clusters/{cluster_id}").json()["id"] == cluster_id
    assert client.get("/api/v1/clusters/9999").status_code == 404


def test_preview_classifies_without_storing(client) -> None:
    response = client.post("/api/v1/triage/preview", json={"message": "Timed out after 30000 ms"})

    assert response.status_code == 200
    assert response.json()["category"] == "TIMEOUT"
    assert response.json()["signature"] == "timed out after <n> ms"
    assert client.get("/api/v1/projects/1/clusters").json() == []


def test_preview_requires_a_message(client) -> None:
    assert client.post("/api/v1/triage/preview", json={"message": ""}).status_code == 422


def test_metrics_endpoint(client) -> None:
    seed(client)
    body = client.get("/metrics").text
    assert "triage_events_processed_total" in body


def test_settings_from_env() -> None:
    settings = Settings.from_env(
        {
            "TRIAGE_DATABASE_URL": "postgresql+psycopg://u:p@db/triage",
            "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
            "ANTHROPIC_API_KEY": "sk-test",
        }
    )
    assert settings.kafka_bootstrap_servers == "kafka:9092"
    assert settings.llm_enabled is True
    assert settings.anthropic_model == "claude-opus-5"

    assert Settings.from_env({}).llm_enabled is False
    assert Settings.from_env({"ANTHROPIC_API_KEY": "k", "TRIAGE_LLM_ENABLED": "false"}).llm_enabled is False
