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
import java.util.List;
import java.util.Map;

public class AnalysisSteps {

    private final ScenarioContext context;

    public AnalysisSteps(ScenarioContext context) {
        this.context = context;
    }

    /** Pattern is oldest to newest: P = passed, F = failed, S = skipped. */
    @Given("the test {string} has the CI history {string}")
    public void theTestHasTheHistory(String test, String pattern) {
        context.histories().put(test, pattern);
    }

    @Given("the CI history is uploaded")
    public void theHistoryIsUploaded() {
        int runs = context.histories().values().stream().mapToInt(String::length).max().orElse(0);
        for (int run = 0; run < runs; run++) {
            JUnitReport report = new JUnitReport();
            for (var entry : context.histories().entrySet()) {
                String pattern = entry.getValue();
                if (run < pattern.length()) {
                    report.add(entry.getKey(), String.valueOf(pattern.charAt(run)));
                }
            }
            IngestionSteps.upload(ApiSpec.authenticated(context.currentProject().apiKey()),
                            context.nextCommitSha(), context.uniqueName("build-" + run), report.build())
                    .then().statusCode(201);
        }
    }

    @Given("a build where {string} failed and then passed on retry")
    public void aBuildWithARetry(String test) {
        String xml = new JUnitReport().add(test, "flaky").add("Smoke.ok", "passed").build();
        IngestionSteps.upload(ApiSpec.authenticated(context.currentProject().apiKey()),
                context.nextCommitSha(), null, xml).then().statusCode(201);
    }

    @When("I list the tests with verdict {string}")
    public void iListTestsWithVerdict(String verdict) {
        context.setLastResponse(given(ApiSpec.json())
                .queryParam("verdict", verdict)
                .get("/api/v1/projects/{id}/tests", context.currentProject().id()));
    }

    @Then("the listed tests are:")
    public void theListedTestsAre(DataTable expected) {
        context.lastResponse().then().statusCode(200);
        List<Map<String, Object>> actual = context.lastResponse().jsonPath().getList("$");
        List<Map<String, String>> rows = expected.asMaps();

        assertThat(actual).hasSameSizeAs(rows);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(actual.get(i).get("name")).as("name of row %d", i).isEqualTo(rows.get(i).get("name"));
            assertThat(actual.get(i).get("verdict")).as("verdict of row %d", i).isEqualTo(rows.get(i).get("verdict"));
        }
    }

    @Then("no tests are listed")
    public void noTestsAreListed() {
        context.lastResponse().then().statusCode(200);
        assertThat(context.lastResponse().jsonPath().getList("$")).isEmpty();
    }

    @Then("{string} has {int} inconsistent commit(s)")
    public void hasInconsistentCommits(String name, int expected) {
        List<Map<String, Object>> tests = context.lastResponse().jsonPath().getList("$");
        Map<String, Object> test = tests.stream().filter(t -> name.equals(t.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not listed"));
        assertThat(test.get("inconsistentCommits")).isEqualTo(expected);
    }

    @Then("the flakiness score of {string} is at least {double}")
    public void theScoreIsAtLeast(String name, double minimum) {
        List<Map<String, Object>> tests = context.lastResponse().jsonPath().getList("$");
        Number score = tests.stream().filter(t -> name.equals(t.get("name"))).findFirst()
                .map(t -> (Number) t.get("score"))
                .orElseThrow(() -> new AssertionError(name + " not listed"));
        assertThat(score.doubleValue()).isGreaterThanOrEqualTo(minimum);
    }
}
