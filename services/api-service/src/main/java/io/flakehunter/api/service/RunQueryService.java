package io.flakehunter.api.service;

import io.flakehunter.api.domain.TestCase;
import io.flakehunter.api.domain.TestRun;
import io.flakehunter.api.persistence.AnalyticsQueries;
import io.flakehunter.api.persistence.AnalyticsQueries.HistoryRow;
import io.flakehunter.api.persistence.AnalyticsQueries.RunResultRow;
import io.flakehunter.api.repository.ProjectRepository;
import io.flakehunter.api.repository.TestCaseRepository;
import io.flakehunter.api.repository.TestRunRepository;
import io.flakehunter.api.web.ApiException;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries over runs and test history. */
@Service
@Transactional(readOnly = true)
public class RunQueryService {

    private final ProjectRepository projects;
    private final TestRunRepository runs;
    private final TestCaseRepository testCases;
    private final AnalyticsQueries queries;

    public RunQueryService(ProjectRepository projects, TestRunRepository runs, TestCaseRepository testCases,
                           AnalyticsQueries queries) {
        this.projects = projects;
        this.runs = runs;
        this.testCases = testCases;
        this.queries = queries;
    }

    public RunPage listRuns(long projectId, int page, int size) {
        requireProject(projectId);
        List<TestRun> items = runs.findByProjectIdOrderByIdDesc(projectId, PageRequest.of(page, size));
        return new RunPage(items, page, size, runs.countByProjectId(projectId));
    }

    public RunWithResults getRun(long projectId, long runId) {
        TestRun run = runs.findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> ApiException.notFound("Run " + runId));
        return new RunWithResults(run, queries.resultsOfRun(runId));
    }

    public TestCase getTest(long projectId, long testId) {
        return testCases.findByIdAndProjectId(testId, projectId)
                .orElseThrow(() -> ApiException.notFound("Test " + testId));
    }

    public List<HistoryRow> history(long testId, int limit) {
        return queries.testHistory(testId, limit);
    }

    public long countTests(long projectId) {
        return testCases.countByProjectId(projectId);
    }

    public long countQuarantined(long projectId) {
        return testCases.countByProjectIdAndQuarantinedTrue(projectId);
    }

    private void requireProject(long projectId) {
        if (!projects.existsById(projectId)) {
            throw ApiException.notFound("Project " + projectId);
        }
    }

    public record RunPage(List<TestRun> items, int page, int size, long total) {
    }

    public record RunWithResults(TestRun run, List<RunResultRow> results) {
    }
}
