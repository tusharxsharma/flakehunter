package io.flakehunter.api.analysis;

import io.flakehunter.api.domain.TestStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores how flaky a test is from its recent execution history. Pure function: no I/O,
 * no framework, fully deterministic, which makes it easy to unit- and property-test.
 *
 * <h2>Signals</h2>
 * <ol>
 *   <li><b>Same-commit inconsistency</b>: if a test both passed and failed on the <i>same</i>
 *       commit, the code did not change, so the test (or its environment) is non-deterministic.
 *       This is the strongest evidence and is the approach used by Google's and Meta's
 *       flaky-test tooling.</li>
 *   <li><b>Flip rate</b>: how often the outcome flips between consecutive executions
 *       (PASS→FAIL or FAIL→PASS). Weighted with exponential decay so recent behaviour counts
 *       more than old behaviour. A test that broke once and was fixed flips twice and scores
 *       low; a test that alternates scores high.</li>
 * </ol>
 *
 * <p>The two signals are combined as a probabilistic OR: {@code 1 - (1 - flip) * (1 - inconsistency)}.
 * Skipped executions carry no information and are ignored. Complexity is O(n) time and
 * O(distinct commits) memory.
 */
public final class FlakinessAnalyzer {

    private final AnalysisSettings settings;

    public FlakinessAnalyzer(AnalysisSettings settings) {
        this.settings = settings;
    }

    /**
     * @param history executions ordered oldest to newest
     */
    public FlakinessResult analyze(List<Observation> history) {
        List<Observation> executed = history.stream()
                .filter(o -> o.status() != TestStatus.SKIPPED)
                .toList();
        int n = executed.size();
        if (n == 0) {
            return new FlakinessResult(0.0, Verdict.INSUFFICIENT_DATA, 0.0, 0.0, 0, 0, null);
        }

        int failures = 0;
        double weightedFlips = 0.0;
        double totalWeight = 0.0;
        Map<String, int[]> outcomesByCommit = new HashMap<>();

        for (int i = 0; i < n; i++) {
            Observation current = executed.get(i);
            boolean failed = current.status() == TestStatus.FAILED;
            if (failed) {
                failures++;
            }
            int[] counts = outcomesByCommit.computeIfAbsent(current.commitSha(), k -> new int[2]);
            counts[failed ? 1 : 0]++;

            if (i > 0) {
                // The newest transition has age 0 and weight 1; older transitions decay geometrically.
                int age = (n - 1) - i;
                double weight = Math.pow(settings.decay(), age);
                totalWeight += weight;
                if (executed.get(i - 1).status() != current.status()) {
                    weightedFlips += weight;
                }
            }
        }

        double flipRate = totalWeight == 0.0 ? 0.0 : weightedFlips / totalWeight;
        double failureRate = (double) failures / n;

        int commitsWithRepeats = 0;
        int inconsistentCommits = 0;
        for (int[] counts : outcomesByCommit.values()) {
            if (counts[0] + counts[1] >= 2) {
                commitsWithRepeats++;
                if (counts[0] > 0 && counts[1] > 0) {
                    inconsistentCommits++;
                }
            }
        }
        double inconsistencyRatio = commitsWithRepeats == 0 ? 0.0 : (double) inconsistentCommits / commitsWithRepeats;

        double score = round4(1.0 - (1.0 - flipRate) * (1.0 - inconsistencyRatio));
        TestStatus lastStatus = executed.get(n - 1).status();
        Verdict verdict = decideVerdict(executed, n, score, inconsistentCommits);

        return new FlakinessResult(score, verdict, round4(flipRate), round4(failureRate),
                inconsistentCommits, n, lastStatus);
    }

    private Verdict decideVerdict(List<Observation> executed, int n, double score, int inconsistentCommits) {
        if (endsWithFailureStreak(executed, n)) {
            return Verdict.BROKEN;
        }
        if (inconsistentCommits > 0) {
            return Verdict.FLAKY;
        }
        if (n < settings.minRuns()) {
            return Verdict.INSUFFICIENT_DATA;
        }
        return score >= settings.flakyThreshold() ? Verdict.FLAKY : Verdict.STABLE;
    }

    private boolean endsWithFailureStreak(List<Observation> executed, int n) {
        int streak = settings.brokenStreak();
        if (n < streak) {
            return false;
        }
        for (int i = n - streak; i < n; i++) {
            if (executed.get(i).status() != TestStatus.FAILED) {
                return false;
            }
        }
        return true;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
