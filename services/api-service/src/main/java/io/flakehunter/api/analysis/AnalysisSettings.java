package io.flakehunter.api.analysis;

import io.flakehunter.api.config.FlakeHunterProperties;

/**
 * Tuning knobs for {@link FlakinessAnalyzer}.
 *
 * @param decay          weight multiplier per step back in time (0.9 = each older transition counts 10% less)
 * @param minRuns        executions needed before a STABLE/FLAKY verdict is given from the score alone
 * @param flakyThreshold score at or above which a test is FLAKY
 * @param brokenStreak   consecutive trailing failures that make a test BROKEN rather than flaky
 */
public record AnalysisSettings(double decay, int minRuns, double flakyThreshold, int brokenStreak) {

    public static AnalysisSettings defaults() {
        return new AnalysisSettings(0.9, 5, 0.3, 3);
    }

    public static AnalysisSettings from(FlakeHunterProperties.Analysis analysis) {
        return new AnalysisSettings(analysis.decay(), analysis.minRuns(), analysis.flakyThreshold(), analysis.brokenStreak());
    }
}
