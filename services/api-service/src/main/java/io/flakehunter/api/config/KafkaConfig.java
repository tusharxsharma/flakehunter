package io.flakehunter.api.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "flakehunter.outbox.enabled", havingValue = "true")
public class KafkaConfig {

    /** Three partitions: runs from different projects are processed in parallel, per-project order is kept. */
    @Bean
    NewTopic testRunsTopic(FlakeHunterProperties properties) {
        return TopicBuilder.name(properties.outbox().topic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
