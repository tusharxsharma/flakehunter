package io.flakehunter.api.analysis;

public enum Verdict {
    /** Passes consistently (or fails rarely without a pattern). */
    STABLE,
    /** Passes and fails non-deterministically: quarantine candidate. */
    FLAKY,
    /** Fails consistently on recent runs: a real regression, not flakiness. */
    BROKEN,
    /** Not enough executions to judge. */
    INSUFFICIENT_DATA
}
