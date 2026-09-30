package io.flakehunter.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.flakehunter.api.support.IntegrationTest;
import io.flakehunter.api.support.JUnitXml;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

/** Proves the full path: HTTP upload -> DB transaction with outbox row -> relay -> Kafka topic. */
@EmbeddedKafka(partitions = 1, topics = OutboxKafkaIT.TOPIC)
@TestPropertySource(properties = {
        "flakehunter.outbox.enabled=true",
        "flakehunter.outbox.poll-interval-ms=100"
})
class OutboxKafkaIT extends IntegrationTest {

    static final String TOPIC = "flakehunter.test-runs.v1";

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void relaysIngestedRunToKafka() throws Exception {
        TestProject project = createProject("kafka-demo");
        String body = upload(project, sha(1), "ci-7", JUnitXml.suite("PaymentTest")
                .passed("charges")
                .failed("refunds", "HTTP 503 from payment gateway")
                .build())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int runId = JsonPath.read(body, "$.run.id");

        var props = KafkaTestUtils.consumerProps(broker, "outbox-it", false);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(20));

            assertThat(record.key()).as("partition key = project id").isEqualTo(String.valueOf(project.id()));
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("TestRunIngested");
            assertThat((Integer) JsonPath.read(record.value(), "$.runId")).isEqualTo(runId);
            assertThat((String) JsonPath.read(record.value(), "$.failures[0].message"))
                    .isEqualTo("HTTP 503 from payment gateway");
        }

        awaitOutboxDrained();
    }

    private void awaitOutboxDrained() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Integer pending = jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
            if (pending != null && pending == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Outbox events were not marked as published within 10s");
    }
}
