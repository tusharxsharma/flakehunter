package io.flakehunter.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.flakehunter.api.support.IntegrationTest;
import io.flakehunter.api.support.JUnitXml;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class RunIngestionApiIT extends IntegrationTest {

    private static final String REPORT = JUnitXml.suite("com.shop.CartTest")
            .passed("addsItem")
            .failed("appliesDiscount", "expected 90 but was 100")
            .skipped("usesWallet")
            .build();

    private TestProject project;

    @BeforeEach
    void setUp() throws Exception {
        project = createProject("shop");
    }

    @Test
    void ingestsReportAndReturnsRunSummary() throws Exception {
        upload(project, "ABCDEF1234", "build-1", REPORT)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.run.commitSha").value("abcdef1234"))
                .andExpect(jsonPath("$.run.branch").value("main"))
                .andExpect(jsonPath("$.run.total").value(3))
                .andExpect(jsonPath("$.run.passed").value(1))
                .andExpect(jsonPath("$.run.failed").value(1))
                .andExpect(jsonPath("$.run.skipped").value(1));
    }

    @Test
    void writesAnOutboxEventInTheSameTransaction() throws Exception {
        upload(project, sha(1), null, REPORT).andExpect(status().isCreated());

        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events", String.class);
        assertThat((String) JsonPath.read(payload, "$.eventType")).isEqualTo("TestRunIngested");
        assertThat((Integer) JsonPath.read(payload, "$.failed")).isEqualTo(1);
        assertThat((String) JsonPath.read(payload, "$.failures[0].name")).isEqualTo("appliesDiscount");
    }

    @Nested
    class Idempotency {

        @Test
        void reUploadingTheSameBuildReturnsTheOriginalRun() throws Exception {
            String first = upload(project, sha(1), "ci-42", REPORT)
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            upload(project, sha(1), "ci-42", REPORT)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(true))
                    .andExpect(jsonPath("$.run.id").value((Integer) JsonPath.read(first, "$.run.id")));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM test_results", Integer.class)).isEqualTo(3);
        }

        @Test
        void uploadsWithoutBuildIdAreNeverDeduplicated() throws Exception {
            upload(project, sha(1), null, REPORT).andExpect(status().isCreated());
            upload(project, sha(1), null, REPORT).andExpect(status().isCreated());

            assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class)).isEqualTo(2);
        }

        @Test
        void sameBuildIdInAnotherProjectIsIndependent() throws Exception {
            TestProject other = createProject("other");
            upload(project, sha(1), "ci-1", REPORT).andExpect(status().isCreated());
            upload(other, sha(1), "ci-1", REPORT).andExpect(status().isCreated());
        }
    }

    @Test
    void reusesTestCasesAcrossRuns() throws Exception {
        upload(project, sha(1), null, REPORT).andExpect(status().isCreated());
        upload(project, sha(2), null, REPORT).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_cases", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_results", Integer.class)).isEqualTo(6);
    }

    @Test
    void acceptsMultipleFilesAsMultipart() throws Exception {
        var a = new MockMultipartFile("files", "TEST-A.xml", "application/xml",
                JUnitXml.suite("A").passed("one").build().getBytes(StandardCharsets.UTF_8));
        var b = new MockMultipartFile("files", "TEST-B.xml", "application/xml",
                JUnitXml.suite("B").passed("two").failed("three", "x").build().getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/runs").file(a).file(b)
                        .header("X-API-Key", project.apiKey())
                        .param("commitSha", sha(1))
                        .param("branch", "feature/login"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.total").value(3))
                .andExpect(jsonPath("$.run.branch").value("feature/login"));
    }

    @Test
    void namesTheBrokenFileWhenOneOfManyIsInvalid() throws Exception {
        var good = new MockMultipartFile("files", "good.xml", "application/xml",
                JUnitXml.suite("A").passed("one").build().getBytes(StandardCharsets.UTF_8));
        var bad = new MockMultipartFile("files", "bad.xml", "application/xml", "<oops".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/runs").file(good).file(bad)
                        .header("X-API-Key", project.apiKey())
                        .param("commitSha", sha(1))
                        .param("branch", "main"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("bad.xml")));
    }

    @Nested
    class Authentication {

        @Test
        void missingApiKeyIs401() throws Exception {
            mvc.perform(post("/api/v1/runs").param("commitSha", sha(1)).param("branch", "main")
                            .contentType(MediaType.APPLICATION_XML).content(REPORT))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("missing_api_key"));
        }

        @Test
        void unknownApiKeyIs401() throws Exception {
            upload(new TestProject(project.id(), "fh_not-a-real-key"), sha(1), null, REPORT)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid_api_key"));
        }
    }

    @Nested
    class Validation {

        @Test
        void invalidCommitShaIs400() throws Exception {
            upload(project, "not-a-sha", null, REPORT)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("commitSha")));
        }

        @Test
        void missingBranchIs400() throws Exception {
            mvc.perform(post("/api/v1/runs").header("X-API-Key", project.apiKey()).param("commitSha", sha(1))
                            .contentType(MediaType.APPLICATION_XML).content(REPORT))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void malformedXmlIs422() throws Exception {
            upload(project, sha(1), null, "<testsuite><testcase name=\"x\">")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("invalid_report"));
        }

        @Test
        void xxePayloadIsRejected() throws Exception {
            String xxe = """
                    <?xml version="1.0"?>
                    <!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                    <testsuite><testcase name="a"><failure message="&x;"/></testcase></testsuite>""";
            upload(project, sha(1), null, xxe)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail", containsString("DOCTYPE")));
        }

        @Test
        void reportWithoutTestsIs422() throws Exception {
            upload(project, sha(1), null, "<testsuites/>")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("empty_report"));
        }

        @Test
        void emptyBodyIs400() throws Exception {
            upload(project, sha(1), null, "")
                    .andExpect(status().isBadRequest());
        }

        @Test
        void oversizedReportIs413() throws Exception {
            String huge = "<testsuite>" + " ".repeat(5 * 1024 * 1024) + "</testsuite>";
            upload(project, sha(1), null, huge)
                    .andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.code").value("payload_too_large"));
        }

        @Test
        void unsupportedContentTypeIs415() throws Exception {
            mvc.perform(post("/api/v1/runs").header("X-API-Key", project.apiKey())
                            .param("commitSha", sha(1)).param("branch", "main")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnsupportedMediaType());
        }
    }

    @Nested
    class Queries {

        @Test
        void listsRunsNewestFirstWithPagination() throws Exception {
            for (int i = 1; i <= 3; i++) {
                upload(project, sha(i), "b" + i, REPORT).andExpect(status().isCreated());
            }

            mvc.perform(get("/api/v1/projects/{id}/runs", project.id()).param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[0].buildId").value("b3"))
                    .andExpect(jsonPath("$.total").value(3));

            mvc.perform(get("/api/v1/projects/{id}/runs", project.id()).param("size", "2").param("page", "1"))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].buildId").value("b1"));
        }

        @Test
        void rejectsInvalidPageSize() throws Exception {
            mvc.perform(get("/api/v1/projects/{id}/runs", project.id()).param("size", "1000"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void runDetailListsFailuresFirst() throws Exception {
            String body = upload(project, sha(1), null, REPORT).andReturn().getResponse().getContentAsString();
            int runId = JsonPath.read(body, "$.run.id");

            mvc.perform(get("/api/v1/projects/{p}/runs/{r}", project.id(), runId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results", hasSize(3)))
                    .andExpect(jsonPath("$.results[0].status").value("FAILED"))
                    .andExpect(jsonPath("$.results[0].failureMessage").value("expected 90 but was 100"))
                    .andExpect(jsonPath("$.results[1].status").value("SKIPPED"))
                    .andExpect(jsonPath("$.results[2].status").value("PASSED"));
        }

        @Test
        void aRunIsNotVisibleThroughAnotherProject() throws Exception {
            TestProject other = createProject("other");
            String body = upload(project, sha(1), null, REPORT).andReturn().getResponse().getContentAsString();
            int runId = JsonPath.read(body, "$.run.id");

            mvc.perform(get("/api/v1/projects/{p}/runs/{r}", other.id(), runId))
                    .andExpect(status().isNotFound());
        }

        @Test
        void runsOfUnknownProjectIs404() throws Exception {
            mvc.perform(get("/api/v1/projects/424242/runs")).andExpect(status().isNotFound());
        }
    }
}
