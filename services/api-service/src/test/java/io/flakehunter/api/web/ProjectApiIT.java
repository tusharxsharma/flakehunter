package io.flakehunter.api.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.flakehunter.api.support.IntegrationTest;
import io.flakehunter.api.support.JUnitXml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class ProjectApiIT extends IntegrationTest {

    @Test
    void createsProjectAndReturnsApiKeyOnce() throws Exception {
        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"checkout-web\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/projects/")))
                .andExpect(jsonPath("$.name").value("checkout-web"))
                .andExpect(jsonPath("$.apiKey", startsWith("fh_")));

        // The key is not retrievable afterwards
        mvc.perform(get("/api/v1/projects"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].apiKey").doesNotExist());
    }

    @Test
    void storesOnlyTheHashOfTheApiKey() throws Exception {
        TestProject project = createProject("secure-project");

        String stored = jdbc.queryForObject("SELECT api_key_hash FROM projects WHERE id = ?", String.class, project.id());

        org.assertj.core.api.Assertions.assertThat(stored).hasSize(64).doesNotContain(project.apiKey());
    }

    @Test
    void rejectsDuplicateNamesWithConflict() throws Exception {
        createProject("payments");

        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"payments\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "Has Spaces", "UPPER", "-leading-dash", "semi;colon"})
    void validatesProjectName(String name) throws Exception {
        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsProjectsAlphabetically() throws Exception {
        createProject("zeta");
        createProject("alpha");

        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("alpha"))
                .andExpect(jsonPath("$[1].name").value("zeta"));
    }

    @Test
    void summaryOfNewProjectIsEmpty() throws Exception {
        TestProject project = createProject("fresh");

        mvc.perform(get("/api/v1/projects/{id}", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(0))
                .andExpect(jsonPath("$.totalTests").value(0))
                .andExpect(jsonPath("$.lastRun").doesNotExist());
    }

    @Test
    void summaryReflectsUploadedRuns() throws Exception {
        TestProject project = createProject("summary");
        upload(project, sha(1), null, JUnitXml.suite("S").passed("a").passed("b").failed("c", "boom").build())
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/projects/{id}", project.id()))
                .andExpect(jsonPath("$.totalRuns").value(1))
                .andExpect(jsonPath("$.totalTests").value(3))
                .andExpect(jsonPath("$.runsInWindow").value(1))
                .andExpect(jsonPath("$.passRate").value(0.6667))
                .andExpect(jsonPath("$.lastRun.failed").value(1));
    }

    @Test
    void unknownProjectIs404ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/projects/999"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("not_found"));
    }
}
