package io.flakehunter.api.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.flakehunter.api.domain.TestStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FlakinessAnalyzerTest {

    private final FlakinessAnalyzer analyzer = new FlakinessAnalyzer(AnalysisSettings.defaults());

    private FlakinessResult analyze(String outcomes) {
        return analyzer.analyze(History.of(outcomes).build());
    }

    @Test
    void noHistoryMeansInsufficientData() {
        FlakinessResult result = analyzer.analyze(List.of());

        assertThat(result.verdict()).isEqualTo(Verdict.INSUFFICIENT_DATA);
        assertThat(result.score()).isZero();
        assertThat(result.lastStatus()).isNull();
    }

    @Test
    void alwaysPassingTestIsStableWithZeroScore() {
        FlakinessResult result = analyze("PPPPPPPPPP");

        assertThat(result.verdict()).isEqualTo(Verdict.STABLE);
        assertThat(result.score()).isZero();
        assertThat(result.failureRate()).isZero();
        assertThat(result.runsAnalyzed()).isEqualTo(10);
    }

    @Test
    void alternatingOutcomesAreMaximallyFlaky() {
        FlakinessResult result = analyze("PFPFPFPFPF");

        assertThat(result.verdict()).isEqualTo(Verdict.FLAKY);
        assertThat(result.flipRate()).isEqualTo(1.0);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.failureRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("consistent recent failures are a real regression (BROKEN), not flakiness")
    void trailingFailureStreakIsBroken() {
        FlakinessResult result = analyze("PPPPPPPFFF");

        assertThat(result.verdict()).isEqualTo(Verdict.BROKEN);
        assertThat(result.lastStatus()).isEqualTo(TestStatus.FAILED);
    }

    @Test
    @DisplayName("a test that broke once and was fixed is not flaky")
    void singleRegressionThatWasFixedStaysStable() {
        FlakinessResult result = analyze("PPPFFPPPPPPPPPPPPPPP");

        assertThat(result.verdict()).isEqualTo(Verdict.STABLE);
        assertThat(result.score()).isLessThan(AnalysisSettings.defaults().flakyThreshold());
    }

    @Test
    @DisplayName("pass + fail on the same commit is flaky even with very little data")
    void sameCommitInconsistencyIsFlakyEvenBelowMinRuns() {
        List<Observation> history = new History().sameCommit("abc123", "FP").build();

        FlakinessResult result = analyzer.analyze(history);

        assertThat(result.verdict()).isEqualTo(Verdict.FLAKY);
        assertThat(result.inconsistentCommits()).isEqualTo(1);
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void repeatedPassesOnOneCommitAreNotInconsistent() {
        List<Observation> history = new History().sameCommit("abc123", "PPP").sameCommit("def456", "PP").build();

        FlakinessResult result = analyzer.analyze(history);

        assertThat(result.inconsistentCommits()).isZero();
        assertThat(result.verdict()).isEqualTo(Verdict.STABLE);
    }

    @Test
    void skippedExecutionsAreIgnored() {
        assertThat(analyze("PSPSSPSPSP")).isEqualTo(analyze("PPPPP"));
    }

    @Test
    void onlySkippedExecutionsIsInsufficientData() {
        assertThat(analyze("SSSS").verdict()).isEqualTo(Verdict.INSUFFICIENT_DATA);
    }

    @Test
    void fewRunsWithoutEvidenceIsInsufficientData() {
        assertThat(analyze("PFP").verdict()).isEqualTo(Verdict.INSUFFICIENT_DATA);
    }

    @Test
    @DisplayName("recent flips weigh more than old flips (exponential decay)")
    void recentInstabilityScoresHigherThanOldInstability() {
        FlakinessResult oldFlips = analyze("PFPFPPPPPPPPPPPPPPPP");
        FlakinessResult recentFlips = analyze("PPPPPPPPPPPPPPPPFPFP");

        assertThat(recentFlips.score()).isGreaterThan(oldFlips.score());
        assertThat(oldFlips.verdict()).isEqualTo(Verdict.STABLE);
        assertThat(recentFlips.verdict()).isEqualTo(Verdict.FLAKY);
    }

    @Test
    void withoutDecayFlipRateIsTheSimpleProportion() {
        var noDecay = new FlakinessAnalyzer(new AnalysisSettings(1.0, 5, 0.3, 3));

        // 9 transitions, 2 of them flips
        FlakinessResult result = noDecay.analyze(History.of("PPPPFPPPPP").build());

        assertThat(result.flipRate()).isCloseTo(2.0 / 9.0, within(0.0001));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PPPPP,      STABLE",
            "PPPPF,      STABLE",
            "PFFPFFPFFP, FLAKY",
            "FFFFF,      BROKEN",
            "PPFF,       INSUFFICIENT_DATA",
            "PPPFFF,     BROKEN",
            "PFPPFPPFPP, FLAKY"
    })
    void verdictTable(String outcomes, Verdict expected) {
        assertThat(analyze(outcomes).verdict()).isEqualTo(expected);
    }
}
