package io.flakehunter.api.security;

/** The authenticated caller: whoever holds a project's API key acts on behalf of that project. */
public record ProjectPrincipal(long projectId, String projectName) {
}
