package io.flakehunter.api.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.flakehunter.api.domain.TestStatus;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests: instead of hand-picked examples, jqwik generates hundreds of random
 * histories and checks rules that must hold for ALL of them. When a rule breaks, jqwik shrinks
 * the input to the smallest failing history, which makes the bug obvious.
 */
class FlakinessAnalyzerPropertiesTest {

    private final FlakinessAnalyzer analyzer = new FlakinessAnalyzer(AnalysisSettings.defaults());

    @Provide
    Arbitrary<List<Observation>> histories() {
        Arbitrary<TestStatus> status = Arbitraries.of(TestStatus.class);
        Arbitrary<String> commit = Arbitraries.of("a1", "b2", "c3", "d4", "e5", "f6");
        Arbitrary<Observation> observation = Combinators.combine(commit, status)
                .as((c, s) -> new Observation(0, c, s));
        return observation.list().ofMaxSize(60);
    }

    @Property
    @Label("scores and rates are always within [0, 1]")
    void scoresAreBounded(@ForAll("histories") List<Observation> history) {
        FlakinessResult r = analyzer.analyze(history);

        assertThat(r.score()).isBetween(0.0, 1.0);
        assertThat(r.flipRate()).isBetween(0.0, 1.0);
        assertThat(r.failureRate()).isBetween(0.0, 1.0);
    }

    @Property
    @Label("runsAnalyzed equals the number of non-skipped executions")
    void countsOnlyExecutedRuns(@ForAll("histories") List<Observation> history) {
        long executed = history.stream().filter(o -> o.status() != TestStatus.SKIPPED).count();

        assertThat(analyzer.analyze(history).runsAnalyzed()).isEqualTo(executed);
    }

    @Property
    @Label("a test that never fails is never flaky and scores 0")
    void neverFailingIsNeverFlaky(@ForAll @IntRange(min = 0, max = 50) int passes,
                                  @ForAll @IntRange(min = 0, max = 10) int skips) {
        List<Observation> history = new ArrayList<>();
        for (int i = 0; i < passes; i++) {
            history.add(new Observation(i, "c" + (i % 4), TestStatus.PASSED));
        }
        for (int i = 0; i < skips; i++) {
            history.add(new Observation(passes + i, "c0", TestStatus.SKIPPED));
        }

        FlakinessResult r = analyzer.analyze(history);

        assertThat(r.score()).isZero();
        assertThat(r.verdict()).isNotIn(Verdict.FLAKY, Verdict.BROKEN);
    }

    @Property
    @Label("inserting skipped executions never changes the result")
    void skipsAreInvisible(@ForAll("histories") List<Observation> history,
                           @ForAll @IntRange(min = 0, max = 60) int position) {
        List<Observation> withSkip = new ArrayList<>(history);
        withSkip.add(Math.min(position, withSkip.size()), new Observation(0, "zz", TestStatus.SKIPPED));

        assertThat(analyzer.analyze(withSkip)).isEqualTo(analyzer.analyze(history));
    }

    @Property
    @Label("renaming commits consistently never changes the result")
    void commitNamesDoNotMatter(@ForAll("histories") List<Observation> history) {
        List<Observation> renamed = history.stream()
                .map(o -> new Observation(o.runId(), "renamed-" + o.commitSha(), o.status()))
                .toList();

        assertThat(analyzer.analyze(renamed)).isEqualTo(analyzer.analyze(history));
    }

    @Property
    @Label("if any commit both passed and failed, the verdict is FLAKY or BROKEN")
    void sameCommitInconsistencyIsAlwaysFlagged(@ForAll("histories") List<Observation> history) {
        FlakinessResult r = analyzer.analyze(history);

        if (r.inconsistentCommits() > 0) {
            assertThat(r.verdict()).isIn(Verdict.FLAKY, Verdict.BROKEN);
        }
    }

    @Property
    @Label("the analyzer is deterministic")
    void deterministic(@ForAll("histories") List<Observation> history) {
        assertThat(analyzer.analyze(history)).isEqualTo(analyzer.analyze(List.copyOf(history)));
    }
}
