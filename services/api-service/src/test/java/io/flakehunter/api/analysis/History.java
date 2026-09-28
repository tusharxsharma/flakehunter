package io.flakehunter.api.analysis;

import io.flakehunter.api.domain.TestStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Test helper that turns a compact string into an execution history.
 * {@code "PPFP"} = pass, pass, fail, pass on four different commits.
 * {@code "S"} = skipped. Each character is a new run on a new commit unless {@link #sameCommit} is used.
 */
final class History {

    private final List<Observation> observations = new ArrayList<>();
    private long nextRun = 1;

    static History of(String outcomes) {
        History h = new History();
        for (char c : outcomes.toCharArray()) {
            h.add("c" + h.nextRun, c);
        }
        return h;
    }

    History sameCommit(String commit, String outcomes) {
        for (char c : outcomes.toCharArray()) {
            add(commit, c);
        }
        return this;
    }

    List<Observation> build() {
        return List.copyOf(observations);
    }

    private void add(String commit, char c) {
        TestStatus status = switch (c) {
            case 'P' -> TestStatus.PASSED;
            case 'F' -> TestStatus.FAILED;
            case 'S' -> TestStatus.SKIPPED;
            default -> throw new IllegalArgumentException("Unknown outcome " + c);
        };
        observations.add(new Observation(nextRun++, commit, status));
    }
}
