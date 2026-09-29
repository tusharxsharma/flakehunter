package io.flakehunter.api.web;

import io.flakehunter.api.analysis.Verdict;
import io.flakehunter.api.domain.TestCase;
import io.flakehunter.api.security.ProjectPrincipal;
import io.flakehunter.api.service.AnalysisService;
import io.flakehunter.api.service.ProjectAnalysis;
import io.flakehunter.api.service.ProjectService;
import io.flakehunter.api.service.QuarantineService;
import io.flakehunter.api.service.RunQueryService;
import io.flakehunter.api.web.dto.Dtos.HistoryEntryResponse;
import io.flakehunter.api.web.dto.Dtos.QuarantineEntryResponse;
import io.flakehunter.api.web.dto.Dtos.QuarantineRequest;
import io.flakehunter.api.web.dto.Dtos.TestAnalysisResponse;
import io.flakehunter.api.web.dto.Dtos.TestDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Tests")
public class TestController {

    private static final int MAX_HISTORY = 200;

    private final ProjectService projectService;
    private final AnalysisService analysisService;
    private final RunQueryService runQueryService;
    private final QuarantineService quarantineService;

    public TestController(ProjectService projectService, AnalysisService analysisService,
                          RunQueryService runQueryService, QuarantineService quarantineService) {
        this.projectService = projectService;
        this.analysisService = analysisService;
        this.runQueryService = runQueryService;
        this.quarantineService = quarantineService;
    }

    @GetMapping("/api/v1/projects/{projectId}/tests")
    @Operation(summary = "Tests ranked by flakiness score",
            description = "verdict: FLAKY (default), BROKEN, STABLE, INSUFFICIENT_DATA or ALL")
    public List<TestAnalysisResponse> list(@PathVariable long projectId,
                                           @RequestParam(defaultValue = "FLAKY") String verdict,
                                           @RequestParam(defaultValue = "0") double minScore) {
        projectService.get(projectId);
        Verdict filter = parseVerdict(verdict);
        if (minScore < 0 || minScore > 1) {
            throw ApiException.badRequest("minScore must be between 0 and 1");
        }
        return analysisService.analyzeProject(projectId).tests().stream()
                .filter(t -> filter == null || t.result().verdict() == filter)
                .filter(t -> t.result().score() >= minScore)
                .map(TestAnalysisResponse::of)
                .toList();
    }

    @GetMapping("/api/v1/projects/{projectId}/tests/{testId}")
    @Operation(summary = "One test's verdict plus its execution history, newest first")
    public TestDetailResponse get(@PathVariable long projectId, @PathVariable long testId,
                                  @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_HISTORY) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_HISTORY);
        }
        TestCase test = runQueryService.getTest(projectId, testId);
        ProjectAnalysis analysis = analysisService.analyzeProject(projectId);
        var history = runQueryService.history(testId, limit).stream().map(HistoryEntryResponse::of).toList();
        return new TestDetailResponse(TestAnalysisResponse.of(analysisService.analyzeTest(analysis, test)), history);
    }

    @PutMapping("/api/v1/projects/{projectId}/tests/{testId}/quarantine")
    @Operation(summary = "Quarantine a test (requires the project's API key)")
    public QuarantineEntryResponse quarantine(@AuthenticationPrincipal ProjectPrincipal caller,
                                              @PathVariable long projectId, @PathVariable long testId,
                                              @Valid @RequestBody QuarantineRequest request) {
        return QuarantineEntryResponse.of(quarantineService.quarantine(caller, projectId, testId, request.reason()));
    }

    @DeleteMapping("/api/v1/projects/{projectId}/tests/{testId}/quarantine")
    @Operation(summary = "Release a test from quarantine (requires the project's API key)")
    public ResponseEntity<Void> release(@AuthenticationPrincipal ProjectPrincipal caller,
                                        @PathVariable long projectId, @PathVariable long testId) {
        quarantineService.release(caller, projectId, testId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/projects/{projectId}/quarantine")
    @Operation(summary = "Quarantined tests", description = "CI pipelines read this to skip or soft-fail these tests.")
    public List<QuarantineEntryResponse> quarantined(@PathVariable long projectId) {
        return quarantineService.list(projectId).stream().map(QuarantineEntryResponse::of).toList();
    }

    private static Verdict parseVerdict(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.equals("ALL")) {
            return null;
        }
        try {
            return Verdict.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("verdict must be one of FLAKY, BROKEN, STABLE, INSUFFICIENT_DATA, ALL");
        }
    }
}
