package io.flakehunter.api.ingestion;

import io.flakehunter.api.web.ApiException;
import java.util.regex.Pattern;

/** Where a report came from: the commit under test, the branch, and the CI build that produced it. */
public record RunMetadata(String commitSha, String branch, String buildId) {

    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-fA-F]{7,40}$");

    public RunMetadata {
        if (commitSha == null || !COMMIT_SHA.matcher(commitSha).matches()) {
            throw ApiException.badRequest("commitSha must be 7-40 hexadecimal characters");
        }
        if (branch == null || branch.isBlank() || branch.length() > 255) {
            throw ApiException.badRequest("branch is required and must be at most 255 characters");
        }
        if (buildId != null && (buildId.isBlank() || buildId.length() > 255)) {
            throw ApiException.badRequest("buildId must be 1-255 characters when provided");
        }
        commitSha = commitSha.toLowerCase();
        branch = branch.trim();
    }
}
