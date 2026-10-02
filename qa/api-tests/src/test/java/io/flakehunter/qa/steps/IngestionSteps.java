package io.flakehunter.qa.steps;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.flakehunter.qa.support.ApiSpec;
import io.flakehunter.qa.support.JUnitReport;
import io.flakehunter.qa.support.ScenarioContext;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Map;

public class IngestionSteps {

    private static final String PASSING_REPORT = new JUnitReport().add("CartTest.addsItem", "passed").build();

    private final ScenarioContext context;

    public IngestionSteps(ScenarioContext context) {
        this.context = context;
    }

    static Response upload(RequestSpecification spec, String commitSha, String buildId, String xml) {
        RequestSpecification request = given(spec)
                .contentType(ContentType.XML)
                .queryParam("commitSha", commitSha)
                .queryParam("branch", "main")
                .body(xml);
        if (buildId != null) {
            request.queryParam("buildId", buildId);
        }
        return request.post("/api/v1/runs");
    }

    private Response uploadAsCurrentProject(String commitSha, String buildId, String xml) {
        return upload(ApiSpec.authenticated(context.currentProject().apiKey()), commitSha, buildId, xml);
    }

    @When("I upload a report for commit {string} with:")
    public void iUploadAReportWith(String commitSha, DataTable table) {
        JUnitReport report = new JUnitReport();
        for (Map<String, String> row : table.asMaps()) {
            report.add(row.get("test"), row.get("outcome"));
        }
        context.setLastResponse(uploadAsCurrentProject(commitSha, null, report.build()));
    }

    @Then("the run has {int} tests: {int} passed, {int} failed and {int} skipped")
    public void theRunHas(int total, int passed, int failed, int skipped) {
        var run = context.lastResponse().jsonPath();
        assertThat(run.getInt("run.total")).isEqualTo(total);
        assertThat(run.getInt("run.passed")).isEqualTo(passed);
        assertThat(run.getInt("run.failed")).isEqualTo(failed);
        assertThat(run.getInt("run.skipped")).isEqualTo(skipped);
    }

    @Given("I uploaded a passing report for build {string}")
    public void iUploadedAPassingReportForBuild(String buildId) {
        Response response = uploadAsCurrentProject(context.nextCommitSha(), context.uniqueName(buildId), PASSING_REPORT);
        response.then().statusCode(201);
        context.setFirstUpload(response);
    }

    @When("I upload the same report for build {string} again")
    public void iUploadTheSameReportAgain(String buildId) {
        context.setLastResponse(uploadAsCurrentProject(context.nextCommitSha(), context.uniqueName(buildId), PASSING_REPORT));
    }

    @Then("the run is marked as a duplicate of the first upload")
    public void theRunIsADuplicate() {
        var response = context.lastResponse().jsonPath();
        assertThat(response.getBoolean("duplicate")).isTrue();
        assertThat(response.getLong("run.id")).isEqualTo(context.firstUpload().jsonPath().getLong("run.id"));
    }

    @When("I upload a report containing an XXE payload")
    public void iUploadAnXxePayload() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <testsuite><testcase name="leak"><failure message="&secret;"/></testcase></testsuite>""";
        context.setLastResponse(uploadAsCurrentProject(context.nextCommitSha(), null, xxe));
    }

    @When("I upload a report that is not valid XML")
    public void iUploadInvalidXml() {
        context.setLastResponse(uploadAsCurrentProject(context.nextCommitSha(), null, "<testsuite><testcase"));
    }

    @When("I upload a report for the invalid commit {string}")
    public void iUploadForInvalidCommit(String commitSha) {
        context.setLastResponse(uploadAsCurrentProject(commitSha, null, PASSING_REPORT));
    }

    @When("I upload a report without an API key")
    public void iUploadWithoutKey() {
        context.setLastResponse(upload(ApiSpec.json(), context.nextCommitSha(), null, PASSING_REPORT));
    }

    @When("I upload a report with the API key {string}")
    public void iUploadWithKey(String apiKey) {
        context.setLastResponse(upload(ApiSpec.authenticated(apiKey), context.nextCommitSha(), null, PASSING_REPORT));
    }
}
