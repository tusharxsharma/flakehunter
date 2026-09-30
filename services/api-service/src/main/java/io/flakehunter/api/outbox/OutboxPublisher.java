package io.flakehunter.api.outbox;

import io.flakehunter.api.config.FlakeHunterProperties;
import io.flakehunter.api.domain.OutboxEvent;
import io.flakehunter.api.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relays outbox rows to Kafka (the "transactional outbox" pattern).
 *
 * <p>Why not send to Kafka directly inside ingestion? Because a DB commit and a Kafka send cannot be
 * atomic: a crash between them would lose the event or publish an event for a rolled-back run.
 * Writing the event to the DB in the same transaction and relaying it afterwards gives
 * <b>at-least-once</b> delivery; consumers deduplicate by {@code eventId}.
 *
 * <p>Events are sent in id order and the batch stops at the first failure, so per-project order is
 * preserved (the Kafka message key is the project id, which pins a project to one partition).
 */
@Component
@ConditionalOnProperty(name = "flakehunter.outbox.enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final String topic;
    private final int batchSize;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                           FlakeHunterProperties properties, MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.topic = properties.outbox().topic();
        this.batchSize = properties.outbox().batchSize();
        this.published = meterRegistry.counter("flakehunter.outbox.published");
        this.failed = meterRegistry.counter("flakehunter.outbox.failed");
        Gauge.builder("flakehunter.outbox.pending", outbox, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Events waiting to be relayed to Kafka")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${flakehunter.outbox.poll-interval-ms}")
    public void relayPending() {
        Integer sent = tx.execute(status -> publishBatch());
        if (sent != null && sent > 0) {
            log.debug("Relayed {} outbox events to {}", sent, topic);
        }
    }

    /** Publishes one batch inside the caller's transaction. Returns how many were sent. */
    int publishBatch() {
        List<OutboxEvent> batch = outbox.lockNextBatch(batchSize);
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                var record = new ProducerRecord<String, String>(topic, event.getAggregateId(), event.getPayload());
                record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventId", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(10, TimeUnit.SECONDS);
                event.markPublished();
                published.increment();
                sent++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                event.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                failed.increment();
                log.warn("Outbox relay failed for event {} (attempt {}): {}",
                        event.getEventId(), event.getAttempts(), e.getMessage());
                break;
            }
        }
        return sent;
    }
}
