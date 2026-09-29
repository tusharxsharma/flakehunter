package io.flakehunter.api.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.flakehunter.api.support.IntegrationTest;
import io.flakehunter.api.support.JUnitXml;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class QuarantineApiIT extends IntegrationTest {

    private TestProject project;
    private long testId;

    @BeforeEach
    void setUp() throws Exception {
        project = createProject("mobile-app");
        upload(project, sha(1), null, JUnitXml.suite("LoginTest").passed("logsIn").build())
                .andExpect(status().isCreated());
        testId = jdbc.queryForObject("SELECT id FROM test_cases", Long.class);
    }

    private ResultActions quarantine(String apiKey, long projectId, long test, String reason) throws Exception {
        var request = put("/api/v1/projects/{p}/tests/{t}/quarantine", projectId, test)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}");
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        return mvc.perform(request);
    }

    @Test
    void quarantinesAndReleasesATest() throws Exception {
        quarantine(project.apiKey(), project.id(), testId, "Flaky on CI, JIRA QA-123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testKey").value("LoginTest::logsIn"))
                .andExpect(jsonPath("$.reason").value("Flaky on CI, JIRA QA-123"))
                .andExpect(jsonPath("$.quarantinedAt", notNullValue()));

        mvc.perform(get("/api/v1/projects/{p}/quarantine", project.id()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("logsIn"));

        mvc.perform(get("/api/v1/projects/{p}/tests/{t}", project.id(), testId))
                .andExpect(jsonPath("$.test.quarantined").value(true));

        mvc.perform(delete("/api/v1/projects/{p}/tests/{t}/quarantine", project.id(), testId)
                        .header("X-API-Key", project.apiKey()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/projects/{p}/quarantine", project.id()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void quarantineAppearsInTheCachedTestList() throws Exception {
        mvc.perform(get("/api/v1/projects/{p}/tests", project.id()).param("verdict", "ALL"))
                .andExpect(jsonPath("$[0].quarantined").value(false));

        quarantine(project.apiKey(), project.id(), testId, "flaky").andExpect(status().isOk());

        mvc.perform(get("/api/v1/projects/{p}/tests", project.id()).param("verdict", "ALL"))
                .andExpect(jsonPath("$[0].quarantined").value(true));
    }

    @Test
    void requiresAnApiKey() throws Exception {
        quarantine(null, project.id(), testId, "flaky").andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/projects/{p}/tests/{t}/quarantine", project.id(), testId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherProjectsKeyIsForbidden() throws Exception {
        TestProject intruder = createProject("intruder");

        quarantine(intruder.apiKey(), project.id(), testId, "sabotage")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void cannotReachATestThroughTheWrongProjectPath() throws Exception {
        TestProject mine = createProject("mine");

        quarantine(mine.apiKey(), mine.id(), testId, "wrong project")
                .andExpect(status().isNotFound());
    }

    @Test
    void reasonIsRequired() throws Exception {
        quarantine(project.apiKey(), project.id(), testId, "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    @Test
    void quarantineListOfUnknownProjectIs404() throws Exception {
        mvc.perform(get("/api/v1/projects/777/quarantine")).andExpect(status().isNotFound());
    }
}
