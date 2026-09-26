package io.flakehunter.api.domain;

/** Outcome of a single test execution. JUnit {@code <error>} is folded into {@link #FAILED}. */
public enum TestStatus {
    PASSED,
    FAILED,
    SKIPPED
}
