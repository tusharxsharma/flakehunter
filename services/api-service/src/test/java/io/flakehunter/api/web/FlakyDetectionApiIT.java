package io.flakehunter.api.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.flakehunter.api.support.IntegrationTest;
import io.flakehunter.api.support.JUnitXml;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end behaviour of the detection pipeline through the HTTP API:
 * upload a realistic history, then check what the API concludes.
 */
class FlakyDetectionApiIT extends IntegrationTest {

    private TestProject project;

    @BeforeEach
    void seedHistory() throws Exception {
        project = createProject("checkout");
        // 10 CI runs on 10 commits:
        //  - addsItem:        always passes            -> STABLE
        //  - appliesDiscount: passes and fails randomly -> FLAKY
        //  - processesRefund: broke in the last 3 runs  -> BROKEN
        String discountPattern = "PFPPFPFPPF";
        for (int i = 0; i < 10; i++) {
            upload(project, sha(i), "build-" + i, JUnitXml.suite("com.shop.CheckoutTest")
                    .passed("addsItem")
                    .outcome("appliesDiscount", discountPattern.charAt(i) == 'P')
                    .outcome("processesRefund", i < 7)
                    .build())
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void flagsTheFlakyTestByDefault() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}/tests", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("appliesDiscount"))
                .andExpect(jsonPath("$[0].verdict").value("FLAKY"))
                .andExpect(jsonPath("$[0].runsAnalyzed").value(10))
                .andExpect(jsonPath("$[0].failureRate").value(0.4));
    }

    @Test
    void separatesBrokenTestsFromFlakyOnes() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}/tests", project.id()).param("verdict", "broken"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("processesRefund"))
                .andExpect(jsonPath("$[0].lastStatus").value("FAILED"));
    }

    @Test
    void allVerdictsSortedByScore() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}/tests", project.id()).param("verdict", "ALL"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name").value("appliesDiscount"))
                .andExpect(jsonPath("$[2].name").value("addsItem"))
                .andExpect(jsonPath("$[2].score").value(0.0));
    }

    @Test
    void minScoreFiltersLowScores() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}/tests", project.id()).param("verdict", "ALL").param("minScore", "0.5"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void rejectsUnknownVerdictAndOutOfRangeScore() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}/tests", project.id()).param("verdict", "SOMETIMES"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/projects/{id}/tests", project.id()).param("minScore", "2"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summaryCountsFlakyAndBrokenTests() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}", project.id()))
                .andExpect(jsonPath("$.totalRuns").value(10))
                .andExpect(jsonPath("$.totalTests").value(3))
                .andExpect(jsonPath("$.flakyTests").value(1))
                .andExpect(jsonPath("$.brokenTests").value(1));
    }

    @Test
    void testDetailShowsHistoryNewestFirst() throws Exception {
        String list = mvc.perform(get("/api/v1/projects/{id}/tests", project.id()))
                .andReturn().getResponse().getContentAsString();
        int testId = JsonPath.read(list, "$[0].testId");

        mvc.perform(get("/api/v1/projects/{p}/tests/{t}", project.id(), testId).param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.test.verdict").value("FLAKY"))
                .andExpect(jsonPath("$.history", hasSize(5)))
                .andExpect(jsonPath("$.history[0].status").value("FAILED"))
                .andExpect(jsonPath("$.history[0].commitSha").value(sha(9)));
    }

    @Test
    void newUploadInvalidatesTheCachedAnalysis() throws Exception {
        // Prime the cache
        mvc.perform(get("/api/v1/projects/{id}/tests", project.id()).param("verdict", "BROKEN"))
                .andExpect(jsonPath("$", hasSize(1)));

        // The refund test is fixed: three green runs in a row
        for (int i = 10; i < 13; i++) {
            upload(project, sha(i), "build-" + i, JUnitXml.suite("com.shop.CheckoutTest")
                    .passed("addsItem").passed("appliesDiscount").passed("processesRefund").build());
        }

        mvc.perform(get("/api/v1/projects/{id}/tests", project.id()).param("verdict", "BROKEN"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void failThenPassWithinOneRunIsFlagged() throws Exception {
        TestProject retries = createProject("retries");
        upload(retries, sha(100), null, JUnitXml.suite("com.shop.SearchTest")
                .passedOnRetry("findsProducts", "Timeout waiting for search index")
                .passed("sortsByPrice")
                .build())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.passed").value(2));

        mvc.perform(get("/api/v1/projects/{id}/tests", retries.id()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("findsProducts"))
                .andExpect(jsonPath("$[0].inconsistentCommits").value(1));
    }

    @Test
    void unknownTestIs404() throws Exception {
        mvc.perform(get("/api/v1/projects/{p}/tests/{t}", project.id(), 999_999))
                .andExpect(status().isNotFound());
    }
}
