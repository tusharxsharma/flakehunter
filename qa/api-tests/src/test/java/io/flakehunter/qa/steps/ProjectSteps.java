package io.flakehunter.qa.steps;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.flakehunter.qa.support.ApiSpec;
import io.flakehunter.qa.support.ScenarioContext;
import io.flakehunter.qa.support.ScenarioContext.Project;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;

public class ProjectSteps {

    private final ScenarioContext context;

    public ProjectSteps(ScenarioContext context) {
        this.context = context;
    }

    private Response createProject(String exactName) {
        return given(ApiSpec.json())
                .body(Map.of("name", exactName))
                .post("/api/v1/projects");
    }

    @Given("a project named {string} exists")
    public void aProjectExists(String alias) {
        Response response = createProject(context.uniqueName(alias));
        response.then().statusCode(201);
        context.addProject(alias, new Project(
                response.jsonPath().getLong("id"),
                response.jsonPath().getString("name"),
                response.jsonPath().getString("apiKey")));
    }

    @When("I create a project named {string}")
    public void iCreateAProjectNamed(String alias) {
        Response response = createProject(context.uniqueName(alias));
        context.setLastResponse(response);
        if (response.statusCode() == 201) {
            context.addProject(alias, new Project(
                    response.jsonPath().getLong("id"),
                    response.jsonPath().getString("name"),
                    response.jsonPath().getString("apiKey")));
        }
    }

    @When("I create a project with the exact name {string}")
    public void iCreateAProjectWithTheExactName(String name) {
        context.setLastResponse(createProject(name));
    }

    @When("I create another project named {string}")
    public void iCreateAnotherProjectNamed(String alias) {
        // Same alias as an existing project => same unique name => should conflict
        context.setLastResponse(createProject(context.uniqueName(alias)));
    }

    @Then("the API key starts with {string}")
    public void theApiKeyStartsWith(String prefix) {
        assertThat(context.lastResponse().jsonPath().getString("apiKey")).startsWith(prefix);
    }

    @Then("the project appears in the project list without its API key")
    public void theProjectAppearsInTheListWithoutItsKey() {
        List<Map<String, Object>> projects = given(ApiSpec.json()).get("/api/v1/projects")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        Map<String, Object> mine = projects.stream()
                .filter(p -> p.get("name").equals(context.currentProject().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("project not listed"));
        assertThat(mine).doesNotContainKey("apiKey");
    }

    @When("I request the summary of project {string}")
    public void iRequestTheSummary(String alias) {
        context.setLastResponse(given(ApiSpec.json()).get("/api/v1/projects/{id}", context.project(alias).id()));
    }

    @When("I request the summary of a project that does not exist")
    public void iRequestAMissingProject() {
        context.setLastResponse(given(ApiSpec.json()).get("/api/v1/projects/{id}", Long.MAX_VALUE));
    }

    @Then("the summary shows {int} runs and {int} tests")
    public void theSummaryShows(int runs, int tests) {
        context.lastResponse().then().statusCode(200);
        assertThat(context.lastResponse().jsonPath().getInt("totalRuns")).isEqualTo(runs);
        assertThat(context.lastResponse().jsonPath().getInt("totalTests")).isEqualTo(tests);
    }
}
