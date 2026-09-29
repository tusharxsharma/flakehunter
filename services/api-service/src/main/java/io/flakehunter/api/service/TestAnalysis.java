package io.flakehunter.api.service;

import io.flakehunter.api.analysis.FlakinessResult;

/** A test case together with its flakiness verdict. */
public record TestAnalysis(
        long testId,
        String testKey,
        String suite,
        String name,
        String file,
        boolean quarantined,
        String quarantineReason,
        FlakinessResult result) {
}
