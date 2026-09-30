"""Kafka consumer loop.

Offsets are committed manually, and only AFTER an event has been processed and its database
transaction committed. If the service crashes mid-way, Kafka redelivers the event and the
ProcessedEvent table turns the redelivery into a no-op: at-least-once delivery + idempotent
processing = effectively-once results.
"""

from __future__ import annotations

import json
import logging
import threading
from typing import Any, Protocol

from prometheus_client import Counter

from .service import InvalidEventError, TriageService

log = logging.getLogger(__name__)

EVENTS_REJECTED = Counter("triage_events_rejected_total", "Messages that could not be parsed or validated")


class KafkaMessage(Protocol):
    def value(self) -> bytes | None: ...
    def error(self) -> Any: ...


class KafkaConsumerLike(Protocol):
    def subscribe(self, topics: list[str]) -> None: ...
    def poll(self, timeout: float) -> KafkaMessage | None: ...
    def commit(self, message: KafkaMessage, asynchronous: bool = ...) -> Any: ...
    def close(self) -> None: ...


def process_message(service: TriageService, message: KafkaMessage) -> bool:
    """Handles one message. Returns True when the offset may be committed.

    Malformed messages ("poison pills") are logged and skipped rather than blocking the partition
    forever. A production system would also forward them to a dead-letter topic.
    """
    if message.error():
        log.warning("Kafka error: %s", message.error())
        return False
    raw = message.value()
    try:
        event = json.loads(raw.decode("utf-8")) if raw else None
        service.handle_event(event)
    except (json.JSONDecodeError, UnicodeDecodeError, InvalidEventError) as e:
        EVENTS_REJECTED.inc()
        log.error("Skipping invalid message: %s", e)
    return True


class TriageConsumer:
    """Runs the poll loop on a background thread so it can live next to the HTTP API."""

    def __init__(self, consumer: KafkaConsumerLike, topic: str, service: TriageService) -> None:
        self._consumer = consumer
        self._topic = topic
        self._service = service
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._consumer.subscribe([self._topic])
        self._thread = threading.Thread(target=self.run, name="triage-consumer", daemon=True)
        self._thread.start()

    def run(self) -> None:
        try:
            while not self._stop.is_set():
                message = self._consumer.poll(0.5)
                if message is None:
                    continue
                try:
                    if process_message(self._service, message):
                        self._consumer.commit(message=message, asynchronous=False)
                except Exception:
                    # A transient failure (e.g. database down): do not commit, so the event is redelivered.
                    log.exception("Processing failed; the event will be retried")
                    self._stop.wait(1.0)
        finally:
            self._consumer.close()

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout)


def create_kafka_consumer(bootstrap_servers: str, group_id: str) -> KafkaConsumerLike:
    from confluent_kafka import Consumer  # noqa: PLC0415 - lazy: unit tests never need librdkafka

    return Consumer(
        {
            "bootstrap.servers": bootstrap_servers,
            "group.id": group_id,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )
