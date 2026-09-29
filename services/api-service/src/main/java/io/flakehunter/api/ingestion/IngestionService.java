package io.flakehunter.api.ingestion;

import io.flakehunter.api.config.FlakeHunterProperties;
import io.flakehunter.api.domain.OutboxEvent;
import io.flakehunter.api.domain.TestRun;
import io.flakehunter.api.domain.TestStatus;
import io.flakehunter.api.outbox.TestRunIngestedEvent;
import io.flakehunter.api.persistence.TestCaseWriter;
import io.flakehunter.api.persistence.TestCaseWriter.ResultRow;
import io.flakehunter.api.repository.OutboxEventRepository;
import io.flakehunter.api.repository.TestRunRepository;
import io.flakehunter.api.security.ProjectPrincipal;
import io.flakehunter.api.service.AnalysisService;
import io.flakehunter.api.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns uploaded JUnit reports into a stored run.
 *
 * <p>Flow: validate metadata, short-circuit duplicates (idempotency), parse outside the transaction
 * (CPU work should not hold DB locks), then in ONE transaction write the run, upsert test cases,
 * batch-insert results and append an outbox event. Either all of it is committed or none of it.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MAX_FAILURES_IN_EVENT = 500;

    private final TestRunRepository runs;
    private final OutboxEventRepository outbox;
    private final TestCaseWriter testCaseWriter;
    private final TransactionTemplate tx;
    private final CacheManager cacheManager;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final JUnitXmlParser parser;

    public IngestionService(TestRunRepository runs, OutboxEventRepository outbox, TestCaseWriter testCaseWriter,
                            TransactionTemplate tx, CacheManager cacheManager, JsonMapper jsonMapper,
                            MeterRegistry meterRegistry, FlakeHunterProperties properties) {
        this.runs = runs;
        this.outbox = outbox;
        this.testCaseWriter = testCaseWriter;
        this.tx = tx;
        this.cacheManager = cacheManager;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
        this.parser = new JUnitXmlParser(properties.ingestion().maxFailureMessageLength());
    }

    public IngestionResult ingest(ProjectPrincipal project, RunMetadata metadata, List<? extends ReportSource> reports) {
        Optional<TestRun> existing = findDuplicate(project.projectId(), metadata.buildId());
        if (existing.isPresent()) {
            meterRegistry.counter("flakehunter.ingestion.duplicates").increment();
            return new IngestionResult(existing.get(), true);
        }

        List<ParsedTestCase> cases = parseAll(reports);
        if (cases.isEmpty()) {
            throw ApiException.unprocessable("empty_report", "The report does not contain any test cases");
        }

        TestRun run;
        try {
            run = tx.execute(status -> persist(project, metadata, cases));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent upload of the same build: return the winner's run.
            TestRun winner = findDuplicate(project.projectId(), metadata.buildId()).orElseThrow(() -> e);
            return new IngestionResult(winner, true);
        }

        evictAnalysisCache(project.projectId());
        meterRegistry.counter("flakehunter.ingestion.runs").increment();
        meterRegistry.counter("flakehunter.ingestion.test_results").increment(cases.size());
        log.info("Ingested run {} for project {} ({} tests, {} failed)",
                run.getId(), project.projectName(), run.getTotal(), run.getFailed());
        return new IngestionResult(run, false);
    }

    private Optional<TestRun> findDuplicate(long projectId, String buildId) {
        return buildId == null ? Optional.empty() : runs.findByProjectIdAndBuildId(projectId, buildId);
    }

    private List<ParsedTestCase> parseAll(List<? extends ReportSource> reports) {
        if (reports.isEmpty()) {
            throw ApiException.badRequest("At least one report file is required");
        }
        List<ParsedTestCase> all = new ArrayList<>();
        for (ReportSource report : reports) {
            try (InputStream in = report.open()) {
                all.addAll(parser.parse(in));
            } catch (ReportParseException e) {
                throw reports.size() == 1 ? e : new ReportParseException(report.name() + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new ReportParseException("Could not read " + report.name(), e);
            }
        }
        return all;
    }

    private TestRun persist(ProjectPrincipal project, RunMetadata metadata, List<ParsedTestCase> cases) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        long durationMs = 0;
        for (ParsedTestCase c : cases) {
            switch (c.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
            durationMs += c.durationMs();
        }

        TestRun run = runs.saveAndFlush(new TestRun(project.projectId(), metadata.commitSha(), metadata.branch(),
                metadata.buildId(), cases.size(), passed, failed, skipped, durationMs));

        Map<String, Long> testIds = testCaseWriter.upsertTestCases(project.projectId(), cases);

        List<ResultRow> rows = new ArrayList<>(cases.size());
        List<TestRunIngestedEvent.Failure> failures = new ArrayList<>();
        for (ParsedTestCase c : cases) {
            long testId = testIds.get(c.testKey());
            // In-run retries: each failed attempt is its own observation, before the final outcome.
            for (int i = 0; i < c.extraFailedAttempts(); i++) {
                rows.add(new ResultRow(testId, TestStatus.FAILED, 0, c.failureMessage()));
            }
            rows.add(new ResultRow(testId, c.status(), c.durationMs(),
                    c.status() == TestStatus.FAILED || c.extraFailedAttempts() > 0 ? c.failureMessage() : null));

            boolean passedOnRetry = c.status() != TestStatus.FAILED && c.extraFailedAttempts() > 0;
            if ((c.status() == TestStatus.FAILED || passedOnRetry) && failures.size() < MAX_FAILURES_IN_EVENT) {
                failures.add(new TestRunIngestedEvent.Failure(testId, c.testKey(), c.suite(), c.name(),
                        c.failureMessage(), passedOnRetry));
            }
        }
        testCaseWriter.insertResults(run.getId(), rows);

        var event = new TestRunIngestedEvent(UUID.randomUUID(), TestRunIngestedEvent.TYPE, Instant.now(),
                project.projectId(), project.projectName(), run.getId(), run.getCommitSha(), run.getBranch(),
                run.getBuildId(), run.getTotal(), passed, failed, skipped, failures);
        outbox.save(new OutboxEvent(event.eventId(), "project", String.valueOf(project.projectId()),
                TestRunIngestedEvent.TYPE, jsonMapper.writeValueAsString(event)));
        return run;
    }

    private void evictAnalysisCache(long projectId) {
        Cache cache = cacheManager.getCache(AnalysisService.CACHE_NAME);
        if (cache != null) {
            cache.evict(projectId);
        }
    }

    public record IngestionResult(TestRun run, boolean duplicate) {
    }
}
