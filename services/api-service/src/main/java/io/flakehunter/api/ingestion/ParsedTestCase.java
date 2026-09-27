package io.flakehunter.api.ingestion;

import io.flakehunter.api.domain.TestStatus;

/**
 * One {@code <testcase>} from a JUnit report.
 *
 * @param extraFailedAttempts failed attempts recorded by a retrying runner before the final outcome
 *                            (Surefire {@code <flakyFailure>}/{@code <rerunFailure>}). A test that failed
 *                            and then passed within the same run is strong evidence of flakiness.
 */
public record ParsedTestCase(
        String suite,
        String name,
        String file,
        TestStatus status,
        long durationMs,
        String failureMessage,
        int extraFailedAttempts) {

    public String testKey() {
        return suite + "::" + name;
    }
}
