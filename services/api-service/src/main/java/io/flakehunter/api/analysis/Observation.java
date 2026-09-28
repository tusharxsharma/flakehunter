package io.flakehunter.api.analysis;

import io.flakehunter.api.domain.TestStatus;

/** One execution of a test, as seen by the analyzer. */
public record Observation(long runId, String commitSha, TestStatus status) {
}
