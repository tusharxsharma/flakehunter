package io.flakehunter.api.analysis;

import io.flakehunter.api.domain.TestStatus;

/**
 * @param score               0.0 (perfectly deterministic) .. 1.0 (maximally flaky)
 * @param flipRate            recency-weighted share of consecutive executions whose outcome changed
 * @param failureRate         share of executions that failed
 * @param inconsistentCommits commits on which the test both passed and failed (the code did not change!)
 * @param runsAnalyzed        executions considered (skipped executions are ignored)
 * @param lastStatus          outcome of the most recent execution, or null if none
 */
public record FlakinessResult(
        double score,
        Verdict verdict,
        double flipRate,
        double failureRate,
        int inconsistentCommits,
        int runsAnalyzed,
        TestStatus lastStatus) {
}
