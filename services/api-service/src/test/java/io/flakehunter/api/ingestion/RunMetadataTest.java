package io.flakehunter.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flakehunter.api.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RunMetadataTest {

    @Test
    void normalisesCommitShaAndBranch() {
        RunMetadata m = new RunMetadata("ABCDEF1", "  main ", "build-1");

        assertThat(m.commitSha()).isEqualTo("abcdef1");
        assertThat(m.branch()).isEqualTo("main");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc12", "not-a-sha", "zzzzzzz", "0123456789012345678901234567890123456789ff"})
    void rejectsInvalidCommitSha(String sha) {
        assertThatThrownBy(() -> new RunMetadata(sha, "main", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("commitSha");
    }

    @Test
    void rejectsMissingOrHugeBranch() {
        assertThatThrownBy(() -> new RunMetadata("abcdef1", " ", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new RunMetadata("abcdef1", "b".repeat(256), null)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsBlankBuildIdButAllowsMissing() {
        assertThat(new RunMetadata("abcdef1", "main", null).buildId()).isNull();
        assertThatThrownBy(() -> new RunMetadata("abcdef1", "main", " ")).isInstanceOf(ApiException.class);
    }
}
