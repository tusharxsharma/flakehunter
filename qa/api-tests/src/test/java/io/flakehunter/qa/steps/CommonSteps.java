package io.flakehunter.qa.steps;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import io.flakehunter.qa.support.ScenarioContext;

/** Assertions that apply to any response. */
public class CommonSteps {

    private final ScenarioContext context;

    public CommonSteps(ScenarioContext context) {
        this.context = context;
    }

    @Then("the response status is {int}")
    public void theResponseStatusIs(int expected) {
        context.lastResponse().then().statusCode(expected);
    }

    @Then("the error code is {string}")
    public void theErrorCodeIs(String code) {
        context.lastResponse().then()
                .contentType("application/problem+json")
                .body("code", org.hamcrest.Matchers.equalTo(code));
    }

    @Then("the response matches the {string} schema")
    public void theResponseMatchesSchema(String schema) {
        context.lastResponse().then().body(matchesJsonSchemaInClasspath("schemas/" + schema + ".json"));
    }

    @Then("the error message mentions {string}")
    public void theErrorMessageMentions(String text) {
        assertThat(context.lastResponse().jsonPath().getString("detail")).containsIgnoringCase(text);
    }
}
