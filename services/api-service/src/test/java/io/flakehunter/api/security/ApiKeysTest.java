package io.flakehunter.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeysTest {

    @Test
    void generatedKeysHaveAPrefixAnd256BitsOfEntropy() {
        String key = ApiKeys.generate();

        assertThat(key).startsWith("fh_");
        // 32 random bytes -> 43 base64url chars without padding
        assertThat(key).hasSize(3 + 43).matches("fh_[A-Za-z0-9_-]+");
    }

    @Test
    void generatedKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            keys.add(ApiKeys.generate());
        }
        assertThat(keys).hasSize(1_000);
    }

    @Test
    void hashIsDeterministicHexSha256() {
        assertThat(ApiKeys.hash("fh_test")).isEqualTo(ApiKeys.hash("fh_test")).hasSize(64).matches("[0-9a-f]+");
        assertThat(ApiKeys.hash("fh_test")).isNotEqualTo(ApiKeys.hash("fh_Test"));
    }
}
