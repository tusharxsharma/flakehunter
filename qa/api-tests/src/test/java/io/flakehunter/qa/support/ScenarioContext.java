package io.flakehunter.qa.support;

import io.restassured.response.Response;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * State shared by all step classes within ONE scenario. PicoContainer creates a fresh instance
 * per scenario, so scenarios never leak state into each other.
 */
public class ScenarioContext {

    /** Unique per scenario, so tests can run repeatedly (and in parallel) against a shared environment. */
    private final String runSuffix = UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, Project> projects = new HashMap<>();
    private final Map<String, String> histories = new LinkedHashMap<>();
    private Project currentProject;
    private Response lastResponse;
    private Response firstUpload;
    private int commitCounter;

    public String uniqueName(String base) {
        return base + "-" + runSuffix;
    }

    public void addProject(String alias, Project project) {
        projects.put(alias, project);
        currentProject = project;
    }

    public Project project(String alias) {
        Project p = projects.get(alias);
        if (p == null) {
            throw new IllegalStateException("No project called '" + alias + "' in this scenario");
        }
        return p;
    }

    public Project currentProject() {
        if (currentProject == null) {
            throw new IllegalStateException("No project has been created in this scenario");
        }
        return currentProject;
    }

    public void setCurrentProject(Project project) {
        this.currentProject = project;
    }

    public Response lastResponse() {
        return lastResponse;
    }

    public void setLastResponse(Response response) {
        this.lastResponse = response;
    }

    public Response firstUpload() {
        return firstUpload;
    }

    public void setFirstUpload(Response response) {
        this.firstUpload = response;
    }

    public Map<String, String> histories() {
        return histories;
    }

    /** A fresh, valid hex commit SHA per upload. */
    public String nextCommitSha() {
        return "%07x".formatted(0xc0de000 + ++commitCounter);
    }

    public record Project(long id, String name, String apiKey) {
    }
}
