package io.flakehunter.api.service;

import java.time.Instant;
import java.util.List;

/**
 * Flakiness analysis of every test seen in a project's recent window of runs.
 * This is the object cached in Redis, keyed by project id.
 *
 * @param tests       sorted by score, highest first
 * @param passRate    passed / (passed + failed) across the window, 0..1
 */
public record ProjectAnalysis(
        long projectId,
        int runsInWindow,
        double passRate,
        List<TestAnalysis> tests,
        Instant computedAt) {
}
