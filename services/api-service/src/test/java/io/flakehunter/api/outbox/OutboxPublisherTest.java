package io.flakehunter.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.flakehunter.api.config.FlakeHunterProperties;
import io.flakehunter.api.domain.OutboxEvent;
import io.flakehunter.api.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

/** Unit test with mocks: the relay logic without a database or broker. */
class OutboxPublisherTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        var properties = new FlakeHunterProperties(null, null, null,
                new FlakeHunterProperties.Outbox(true, "topic", 100, 10));
        publisher = new OutboxPublisher(repository, kafka, mock(TransactionTemplate.class), properties, meters);
    }

    private static OutboxEvent event(String payload) {
        return new OutboxEvent(UUID.randomUUID(), "project", "1", "TestRunIngested", payload);
    }

    @Test
    void marksEventsPublishedAfterKafkaAcknowledges() {
        OutboxEvent a = event("{\"n\":1}");
        OutboxEvent b = event("{\"n\":2}");
        when(repository.lockNextBatch(10)).thenReturn(List.of(a, b));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        int sent = publisher.publishBatch();

        assertThat(sent).isEqualTo(2);
        assertThat(a.getPublishedAt()).isNotNull();
        assertThat(b.getPublishedAt()).isNotNull();
        assertThat(meters.counter("flakehunter.outbox.published").count()).isEqualTo(2.0);
    }

    @Test
    void stopsAtFirstFailureToPreserveOrdering() {
        OutboxEvent a = event("a");
        OutboxEvent b = event("b");
        when(repository.lockNextBatch(10)).thenReturn(List.of(a, b));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        int sent = publisher.publishBatch();

        assertThat(sent).isZero();
        assertThat(a.getPublishedAt()).isNull();
        assertThat(a.getAttempts()).isEqualTo(1);
        assertThat(a.getLastError()).contains("broker down");
        assertThat(b.getAttempts()).as("b must not be sent before a").isZero();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }
}
