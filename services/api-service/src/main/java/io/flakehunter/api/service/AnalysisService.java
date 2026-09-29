package io.flakehunter.api.service;

import io.flakehunter.api.analysis.AnalysisSettings;
import io.flakehunter.api.analysis.FlakinessAnalyzer;
import io.flakehunter.api.analysis.FlakinessResult;
import io.flakehunter.api.analysis.Observation;
import io.flakehunter.api.config.FlakeHunterProperties;
import io.flakehunter.api.domain.TestCase;
import io.flakehunter.api.persistence.AnalyticsQueries;
import io.flakehunter.api.persistence.AnalyticsQueries.ResultRef;
import io.flakehunter.api.persistence.AnalyticsQueries.RunRef;
import io.flakehunter.api.repository.TestCaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the {@link FlakinessAnalyzer} over a sliding window of each project's most recent runs.
 *
 * <p>Cost is O(window x tests) and the result is cached (Redis in production). The cache entry is
 * evicted whenever a new run is ingested or a quarantine flag changes, so reads are fast and never stale.
 */
@Service
public class AnalysisService {

    public static final String CACHE_NAME = "project-analysis";

    private final AnalyticsQueries queries;
    private final TestCaseRepository testCases;
    private final FlakinessAnalyzer analyzer;
    private final int windowSize;

    public AnalysisService(AnalyticsQueries queries, TestCaseRepository testCases, FlakeHunterProperties properties) {
        this.queries = queries;
        this.testCases = testCases;
        this.analyzer = new FlakinessAnalyzer(AnalysisSettings.from(properties.analysis()));
        this.windowSize = properties.analysis().windowSize();
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#projectId")
    @Transactional(readOnly = true)
    public ProjectAnalysis analyzeProject(long projectId) {
        List<RunRef> runs = queries.recentRuns(projectId, windowSize);
        if (runs.isEmpty()) {
            return new ProjectAnalysis(projectId, 0, 0.0, List.of(), Instant.now());
        }

        Map<Long, String> commitByRun = runs.stream().collect(Collectors.toMap(RunRef::id, RunRef::commitSha));
        List<ResultRef> results = queries.resultsForRuns(runs.stream().map(RunRef::id).toList());

        // Group executions per test, keeping chronological order (results are sorted by run, then attempt).
        Map<Long, List<Observation>> historyByTest = new LinkedHashMap<>();
        for (ResultRef r : results) {
            historyByTest.computeIfAbsent(r.testCaseId(), k -> new ArrayList<>())
                    .add(new Observation(r.runId(), commitByRun.get(r.runId()), r.status()));
        }

        Map<Long, TestCase> metadata = testCases.findByIdIn(historyByTest.keySet()).stream()
                .collect(Collectors.toMap(TestCase::getId, Function.identity()));

        List<TestAnalysis> analyses = new ArrayList<>(historyByTest.size());
        historyByTest.forEach((testId, history) -> {
            TestCase tc = metadata.get(testId);
            if (tc != null) {
                analyses.add(toAnalysis(tc, analyzer.analyze(history)));
            }
        });
        analyses.sort(Comparator.comparingDouble((TestAnalysis a) -> a.result().score()).reversed()
                .thenComparing(TestAnalysis::testKey));

        long passed = runs.stream().mapToLong(RunRef::passed).sum();
        long failed = runs.stream().mapToLong(RunRef::failed).sum();
        double passRate = passed + failed == 0 ? 0.0 : Math.round(10_000.0 * passed / (passed + failed)) / 10_000.0;

        return new ProjectAnalysis(projectId, runs.size(), passRate, List.copyOf(analyses), Instant.now());
    }

    /** Analysis of one test, consistent with the project-level view (same window, same cache). */
    public TestAnalysis analyzeTest(ProjectAnalysis projectAnalysis, TestCase testCase) {
        TestAnalysis cached = projectAnalysis.tests().stream()
                .filter(a -> a.testId() == testCase.getId())
                .findFirst()
                .orElse(null);
        if (cached != null) {
            // The quarantine flag may have changed since the analysis was cached
            return new TestAnalysis(cached.testId(), cached.testKey(), cached.suite(), cached.name(), cached.file(),
                    testCase.isQuarantined(), testCase.getQuarantineReason(), cached.result());
        }
        return toAnalysis(testCase, analyzer.analyze(List.of()));
    }

    private static TestAnalysis toAnalysis(TestCase tc, FlakinessResult result) {
        return new TestAnalysis(tc.getId(), tc.getTestKey(), tc.getSuite(), tc.getName(), tc.getFile(),
                tc.isQuarantined(), tc.getQuarantineReason(), result);
    }
}
