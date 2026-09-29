package io.flakehunter.api.web.dto;

import io.flakehunter.api.analysis.FlakinessResult;
import io.flakehunter.api.analysis.Verdict;
import io.flakehunter.api.domain.Project;
import io.flakehunter.api.domain.TestCase;
import io.flakehunter.api.domain.TestRun;
import io.flakehunter.api.domain.TestStatus;
import io.flakehunter.api.persistence.AnalyticsQueries.HistoryRow;
import io.flakehunter.api.persistence.AnalyticsQueries.RunResultRow;
import io.flakehunter.api.service.TestAnalysis;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * API request/response contracts. Records keep them immutable, and keeping entities out of
 * responses means the database schema can change without breaking API clients.
 */
public final class Dtos {

    private Dtos() {
    }

    public record CreateProjectRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{1,63}$",
                    message = "must be 2-64 chars of lowercase letters, digits, '.', '_' or '-', starting with a letter or digit")
            String name) {
    }

    public record ProjectCreatedResponse(long id, String name, Instant createdAt, String apiKey) {
    }

    public record ProjectResponse(long id, String name, Instant createdAt) {
        public static ProjectResponse of(Project p) {
            return new ProjectResponse(p.getId(), p.getName(), p.getCreatedAt());
        }
    }

    public record ProjectSummaryResponse(
            long id,
            String name,
            Instant createdAt,
            long totalRuns,
            long totalTests,
            int runsInWindow,
            double passRate,
            long flakyTests,
            long brokenTests,
            long quarantinedTests,
            RunResponse lastRun) {
    }

    public record RunResponse(long id, String commitSha, String branch, String buildId, int total, int passed,
                              int failed, int skipped, long durationMs, Instant createdAt) {
        public static RunResponse of(TestRun r) {
            return new RunResponse(r.getId(), r.getCommitSha(), r.getBranch(), r.getBuildId(), r.getTotal(),
                    r.getPassed(), r.getFailed(), r.getSkipped(), r.getDurationMs(), r.getCreatedAt());
        }
    }

    public record IngestionResponse(RunResponse run, boolean duplicate) {
    }

    public record RunPageResponse(List<RunResponse> items, int page, int size, long total) {
    }

    public record RunResultResponse(long testId, String testKey, String suite, String name, TestStatus status,
                                    long durationMs, String failureMessage) {
        public static RunResultResponse of(RunResultRow r) {
            return new RunResultResponse(r.testId(), r.testKey(), r.suite(), r.name(), r.status(), r.durationMs(),
                    r.failureMessage());
        }
    }

    public record RunDetailResponse(RunResponse run, List<RunResultResponse> results) {
    }

    public record TestAnalysisResponse(
            long testId,
            String testKey,
            String suite,
            String name,
            String file,
            boolean quarantined,
            String quarantineReason,
            Verdict verdict,
            double score,
            double flipRate,
            double failureRate,
            int inconsistentCommits,
            int runsAnalyzed,
            TestStatus lastStatus) {
        public static TestAnalysisResponse of(TestAnalysis a) {
            FlakinessResult r = a.result();
            return new TestAnalysisResponse(a.testId(), a.testKey(), a.suite(), a.name(), a.file(), a.quarantined(),
                    a.quarantineReason(), r.verdict(), r.score(), r.flipRate(), r.failureRate(),
                    r.inconsistentCommits(), r.runsAnalyzed(), r.lastStatus());
        }
    }

    public record HistoryEntryResponse(long runId, String commitSha, String branch, Instant createdAt,
                                       TestStatus status, long durationMs, String failureMessage) {
        public static HistoryEntryResponse of(HistoryRow h) {
            return new HistoryEntryResponse(h.runId(), h.commitSha(), h.branch(), h.createdAt(), h.status(),
                    h.durationMs(), h.failureMessage());
        }
    }

    public record TestDetailResponse(TestAnalysisResponse test, List<HistoryEntryResponse> history) {
    }

    public record QuarantineRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record QuarantineEntryResponse(long testId, String testKey, String suite, String name, String reason,
                                          Instant quarantinedAt) {
        public static QuarantineEntryResponse of(TestCase t) {
            return new QuarantineEntryResponse(t.getId(), t.getTestKey(), t.getSuite(), t.getName(),
                    t.getQuarantineReason(), t.getQuarantinedAt());
        }
    }
}
