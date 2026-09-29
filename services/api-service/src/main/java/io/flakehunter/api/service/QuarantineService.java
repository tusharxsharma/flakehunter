package io.flakehunter.api.service;

import io.flakehunter.api.domain.TestCase;
import io.flakehunter.api.repository.ProjectRepository;
import io.flakehunter.api.repository.TestCaseRepository;
import io.flakehunter.api.security.ProjectPrincipal;
import io.flakehunter.api.web.ApiException;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarantine = "keep running this test, but stop letting it fail the build".
 * CI pipelines fetch the quarantine list and exclude or soft-fail those tests, which unblocks
 * developers while the flaky test is being fixed.
 */
@Service
public class QuarantineService {

    private final TestCaseRepository testCases;
    private final ProjectRepository projects;

    public QuarantineService(TestCaseRepository testCases, ProjectRepository projects) {
        this.testCases = testCases;
        this.projects = projects;
    }

    @Transactional
    @CacheEvict(cacheNames = AnalysisService.CACHE_NAME, key = "#projectId")
    public TestCase quarantine(ProjectPrincipal caller, long projectId, long testId, String reason) {
        TestCase test = ownedTest(caller, projectId, testId);
        test.quarantine(reason.trim());
        return test;
    }

    @Transactional
    @CacheEvict(cacheNames = AnalysisService.CACHE_NAME, key = "#projectId")
    public void release(ProjectPrincipal caller, long projectId, long testId) {
        ownedTest(caller, projectId, testId).release();
    }

    @Transactional(readOnly = true)
    public List<TestCase> list(long projectId) {
        if (!projects.existsById(projectId)) {
            throw ApiException.notFound("Project " + projectId);
        }
        return testCases.findByProjectIdAndQuarantinedTrueOrderByTestKeyAsc(projectId);
    }

    private TestCase ownedTest(ProjectPrincipal caller, long projectId, long testId) {
        if (caller.projectId() != projectId) {
            throw ApiException.forbidden("This API key belongs to a different project");
        }
        return testCases.findByIdAndProjectId(testId, projectId)
                .orElseThrow(() -> ApiException.notFound("Test " + testId));
    }
}
