package io.flakehunter.api.web;

import io.flakehunter.api.config.FlakeHunterProperties;
import io.flakehunter.api.ingestion.IngestionService;
import io.flakehunter.api.ingestion.IngestionService.IngestionResult;
import io.flakehunter.api.ingestion.ReportSource;
import io.flakehunter.api.ingestion.RunMetadata;
import io.flakehunter.api.security.ProjectPrincipal;
import io.flakehunter.api.service.RunQueryService;
import io.flakehunter.api.web.dto.Dtos.IngestionResponse;
import io.flakehunter.api.web.dto.Dtos.RunDetailResponse;
import io.flakehunter.api.web.dto.Dtos.RunPageResponse;
import io.flakehunter.api.web.dto.Dtos.RunResponse;
import io.flakehunter.api.web.dto.Dtos.RunResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name = "Runs")
public class RunController {

    private static final int MAX_PAGE_SIZE = 100;

    private final IngestionService ingestionService;
    private final RunQueryService runQueryService;
    private final long maxReportBytes;

    public RunController(IngestionService ingestionService, RunQueryService runQueryService,
                         FlakeHunterProperties properties) {
        this.ingestionService = ingestionService;
        this.runQueryService = runQueryService;
        this.maxReportBytes = properties.ingestion().maxReportBytes();
    }

    @PostMapping(path = "/api/v1/runs", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @Operation(summary = "Upload one JUnit XML report as the raw request body",
            description = "Idempotent per buildId: re-uploading the same build returns the original run with 200.")
    public ResponseEntity<IngestionResponse> uploadXml(
            @AuthenticationPrincipal ProjectPrincipal project,
            @RequestParam String commitSha,
            @RequestParam String branch,
            @RequestParam(required = false) String buildId,
            HttpServletRequest request) throws IOException {
        RunMetadata metadata = new RunMetadata(commitSha, branch, buildId);
        byte[] body = readBounded(request.getInputStream());
        ReportSource source = new BytesReportSource("request body", body);
        return respond(ingestionService.ingest(project, metadata, List.of(source)));
    }

    @PostMapping(path = "/api/v1/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload one or more JUnit XML files as multipart 'files' parts",
            description = "Use this for runners that write one XML file per test class, such as Maven Surefire.")
    public ResponseEntity<IngestionResponse> uploadMultipart(
            @AuthenticationPrincipal ProjectPrincipal project,
            @RequestParam String commitSha,
            @RequestParam String branch,
            @RequestParam(required = false) String buildId,
            @RequestPart("files") List<MultipartFile> files) {
        RunMetadata metadata = new RunMetadata(commitSha, branch, buildId);
        List<ReportSource> sources = files.stream()
                .<ReportSource>map(MultipartReportSource::new)
                .toList();
        return respond(ingestionService.ingest(project, metadata, sources));
    }

    @GetMapping("/api/v1/projects/{projectId}/runs")
    @Operation(summary = "List runs, newest first")
    public RunPageResponse list(@PathVariable long projectId,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        var result = runQueryService.listRuns(projectId, page, size);
        return new RunPageResponse(result.items().stream().map(RunResponse::of).toList(),
                result.page(), result.size(), result.total());
    }

    @GetMapping("/api/v1/projects/{projectId}/runs/{runId}")
    @Operation(summary = "Run detail with every test result (failures first)")
    public RunDetailResponse get(@PathVariable long projectId, @PathVariable long runId) {
        var result = runQueryService.getRun(projectId, runId);
        return new RunDetailResponse(RunResponse.of(result.run()),
                result.results().stream().map(RunResultResponse::of).toList());
    }

    private static ResponseEntity<IngestionResponse> respond(IngestionResult result) {
        var body = new IngestionResponse(RunResponse.of(result.run()), result.duplicate());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /** Reads at most maxReportBytes; a larger body is rejected without buffering all of it. */
    private byte[] readBounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxReportBytes + 1));
        if (bytes.length > maxReportBytes) {
            throw ApiException.payloadTooLarge("Report exceeds the " + maxReportBytes + " byte limit");
        }
        if (bytes.length == 0) {
            throw ApiException.badRequest("Request body is empty");
        }
        return bytes;
    }

    private record BytesReportSource(String name, byte[] bytes) implements ReportSource {
        @Override
        public InputStream open() {
            return new ByteArrayInputStream(bytes);
        }
    }

    private record MultipartReportSource(MultipartFile file) implements ReportSource {
        @Override
        public String name() {
            return file.getOriginalFilename() == null ? file.getName() : file.getOriginalFilename();
        }

        @Override
        public InputStream open() throws IOException {
            return file.getInputStream();
        }
    }
}
