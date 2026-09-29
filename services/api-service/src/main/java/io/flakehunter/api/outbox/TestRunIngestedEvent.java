package io.flakehunter.api.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published to Kafka topic {@code flakehunter.test-runs.v1} after every ingested run.
 * Consumers (the Python triage service) use it to cluster failures without polling the API.
 * The {@code .v1} suffix lets the schema evolve by publishing a v2 topic alongside v1.
 */
public record TestRunIngestedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        long projectId,
        String projectName,
        long runId,
        String commitSha,
        String branch,
        String buildId,
        int total,
        int passed,
        int failed,
        int skipped,
        List<Failure> failures) {

    public static final String TYPE = "TestRunIngested";

    public record Failure(long testId, String testKey, String suite, String name, String message, boolean passedOnRetry) {
    }
}
