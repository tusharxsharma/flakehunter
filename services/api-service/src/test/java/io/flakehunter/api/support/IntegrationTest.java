package io.flakehunter.api.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base class for API integration tests: the full Spring application (security, validation,
 * transactions, Flyway migrations) against a real PostgreSQL. Each test starts from empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private CacheManager cacheManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE projects, test_runs, test_cases, test_results, outbox_events RESTART IDENTITY CASCADE");
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    /** Creates a project through the API and returns its credentials. */
    protected TestProject createProject(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TestProject(((Number) JsonPath.read(body, "$.id")).longValue(), JsonPath.read(body, "$.apiKey"));
    }

    /** Uploads a raw XML report. */
    protected ResultActions upload(TestProject project, String commitSha, String buildId, String xml) throws Exception {
        var request = post("/api/v1/runs")
                .header("X-API-Key", project.apiKey())
                .param("commitSha", commitSha)
                .param("branch", "main")
                .contentType(MediaType.APPLICATION_XML)
                .content(xml);
        if (buildId != null) {
            request.param("buildId", buildId);
        }
        return mvc.perform(request);
    }

    /** Commit SHAs must be hex; derive a valid one from a counter. */
    protected static String sha(int n) {
        return "%07x".formatted(0xabc0000 + n);
    }

    public record TestProject(long id, String apiKey) {
    }
}
