package io.flakehunter.api.web;

import io.flakehunter.api.analysis.Verdict;
import io.flakehunter.api.domain.Project;
import io.flakehunter.api.service.AnalysisService;
import io.flakehunter.api.service.ProjectAnalysis;
import io.flakehunter.api.service.ProjectService;
import io.flakehunter.api.service.RunQueryService;
import io.flakehunter.api.web.dto.Dtos.CreateProjectRequest;
import io.flakehunter.api.web.dto.Dtos.ProjectCreatedResponse;
import io.flakehunter.api.web.dto.Dtos.ProjectResponse;
import io.flakehunter.api.web.dto.Dtos.ProjectSummaryResponse;
import io.flakehunter.api.web.dto.Dtos.RunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects")
public class ProjectController {

    private final ProjectService projectService;
    private final AnalysisService analysisService;
    private final RunQueryService runQueryService;

    public ProjectController(ProjectService projectService, AnalysisService analysisService,
                             RunQueryService runQueryService) {
        this.projectService = projectService;
        this.analysisService = analysisService;
        this.runQueryService = runQueryService;
    }

    @PostMapping
    @Operation(summary = "Create a project", description = "Returns the project's API key. It is shown only once.")
    public ResponseEntity<ProjectCreatedResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        var created = projectService.create(request.name());
        Project p = created.project();
        return ResponseEntity.created(URI.create("/api/v1/projects/" + p.getId()))
                .body(new ProjectCreatedResponse(p.getId(), p.getName(), p.getCreatedAt(), created.apiKey()));
    }

    @GetMapping
    @Operation(summary = "List projects")
    public List<ProjectResponse> list() {
        return projectService.list().stream().map(ProjectResponse::of).toList();
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Project summary", description = "Run counts, pass rate and flaky/broken/quarantined totals.")
    public ProjectSummaryResponse get(@PathVariable long projectId) {
        Project p = projectService.get(projectId);
        ProjectAnalysis analysis = analysisService.analyzeProject(projectId);
        var runs = runQueryService.listRuns(projectId, 0, 1);
        RunResponse lastRun = runs.items().isEmpty() ? null : RunResponse.of(runs.items().getFirst());
        long flaky = analysis.tests().stream().filter(t -> t.result().verdict() == Verdict.FLAKY).count();
        long broken = analysis.tests().stream().filter(t -> t.result().verdict() == Verdict.BROKEN).count();
        long quarantined = runQueryService.countQuarantined(projectId);
        return new ProjectSummaryResponse(p.getId(), p.getName(), p.getCreatedAt(), runs.total(),
                runQueryService.countTests(projectId), analysis.runsInWindow(), analysis.passRate(),
                flaky, broken, quarantined, lastRun);
    }
}
