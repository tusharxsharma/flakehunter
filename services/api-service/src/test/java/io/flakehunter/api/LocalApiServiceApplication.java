package io.flakehunter.api;

import io.flakehunter.api.support.EmbeddedPostgresDatabase;
import org.springframework.boot.SpringApplication;

/**
 * Runs the API locally with zero infrastructure: an embedded PostgreSQL, an in-memory cache and
 * no Kafka. Start it with {@code ./mvnw spring-boot:test-run} and open http://localhost:8080/swagger-ui.html.
 *
 * <p>Lives in the test sources so the embedded database never ships in the production jar.
 */
public final class LocalApiServiceApplication {

    private LocalApiServiceApplication() {
    }

    public static void main(String[] args) {
        System.setProperty("spring.datasource.url", EmbeddedPostgresDatabase.jdbcUrl());
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("spring.cache.type", "simple");
        System.setProperty("management.health.redis.enabled", "false");
        System.setProperty("flakehunter.outbox.enabled", "false");
        SpringApplication.from(ApiServiceApplication::main).run(args);
    }
}
