package io.flakehunter.qa.steps;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.flakehunter.qa.support.ApiSpec;
import io.flakehunter.qa.support.ScenarioContext;
import io.flakehunter.qa.support.ScenarioContext.Project;
import java.util.List;
import java.util.Map;

public class QuarantineSteps {

    private final ScenarioContext context;

    public QuarantineSteps(ScenarioContext context) {
        this.context = context;
    }

    private long testIdOf(Project project, String name) {
        List<Map<String, Object>> tests = given(ApiSpec.json())
                .queryParam("verdict", "ALL")
                .get("/api/v1/projects/{id}/tests", project.id())
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        return tests.stream()
                .filter(t -> name.equals(t.get("name")))
                .map(t -> ((Number) t.get("testId")).longValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test " + name + " not found in project " + project.name()));
    }

    @When("I quarantine {string} with reason {string}")
    public void iQuarantine(String test, String reason) {
        Project project = context.currentProject();
        context.setLastResponse(given(ApiSpec.authenticated(project.apiKey()))
                .body(Map.of("reason", reason))
                .put("/api/v1/projects/{p}/tests/{t}/quarantine", project.id(), testIdOf(project, test)));
    }

    @When("I release {string} from quarantine")
    public void iRelease(String test) {
        Project project = context.currentProject();
        context.setLastResponse(given(ApiSpec.authenticated(project.apiKey()))
                .delete("/api/v1/projects/{p}/tests/{t}/quarantine", project.id(), testIdOf(project, test)));
    }

    @When("project {string} tries to quarantine {string} in project {string}")
    public void anotherProjectTriesToQuarantine(String attackerAlias, String test, String ownerAlias) {
        Project attacker = context.project(attackerAlias);
        Project owner = context.project(ownerAlias);
        context.setLastResponse(given(ApiSpec.authenticated(attacker.apiKey()))
                .body(Map.of("reason", "sabotage"))
                .put("/api/v1/projects/{p}/tests/{t}/quarantine", owner.id(), testIdOf(owner, test)));
    }

    @Then("the quarantine list contains {string} with reason {string}")
    public void theQuarantineListContains(String test, String reason) {
        List<Map<String, Object>> entries = quarantineList();
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.get("name")).isEqualTo(test);
            assertThat(e.get("reason")).isEqualTo(reason);
        });
    }

    @Then("the quarantine list is empty")
    public void theQuarantineListIsEmpty() {
        assertThat(quarantineList()).isEmpty();
    }

    private List<Map<String, Object>> quarantineList() {
        return given(ApiSpec.json())
                .get("/api/v1/projects/{id}/quarantine", context.currentProject().id())
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
    }
}
