package io.flakehunter.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration bound from the {@code flakehunter.*} keys in application.yml.
 * Validated at startup so a bad value fails fast instead of misbehaving at runtime.
 */
@Validated
@ConfigurationProperties(prefix = "flakehunter")
public record FlakeHunterProperties(
        @Valid Analysis analysis,
        @Valid Ingestion ingestion,
        @Valid RateLimit rateLimit,
        @Valid Outbox outbox) {

    public record Analysis(
            @Min(2) int windowSize,
            @DecimalMin("0.01") @DecimalMax("1.0") double decay,
            @Min(1) int minRuns,
            @DecimalMin("0.0") @DecimalMax("1.0") double flakyThreshold,
            @Min(1) int brokenStreak) {
    }

    public record Ingestion(@Min(1024) long maxReportBytes, @Min(100) int maxFailureMessageLength) {
    }

    public record RateLimit(boolean enabled, @Min(1) long capacity, @DecimalMin("0.001") double refillPerSecond) {
    }

    public record Outbox(boolean enabled, @NotBlank String topic, @Min(10) long pollIntervalMs, @Min(1) int batchSize) {
    }
}
