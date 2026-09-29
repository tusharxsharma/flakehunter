package io.flakehunter.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.flakehunter.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "flakehunter.rate-limit.enabled=true",
        "flakehunter.rate-limit.capacity=3",
        "flakehunter.rate-limit.refill-per-second=0.01"
})
class RateLimitIT extends IntegrationTest {

    @Test
    void returns429WithRetryAfterOnceTheBucketIsEmpty() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/projects").with(r -> {
                        r.setRemoteAddr("10.0.0.1");
                        return r;
                    }))
                    .andExpect(status().isOk())
                    .andExpect(header().string("RateLimit-Limit", "3"));
        }

        mvc.perform(get("/api/v1/projects").with(r -> {
                    r.setRemoteAddr("10.0.0.1");
                    return r;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));

        // A different client is unaffected
        mvc.perform(get("/api/v1/projects").with(r -> {
                    r.setRemoteAddr("10.0.0.2");
                    return r;
                }))
                .andExpect(status().isOk());
    }

    @Test
    void doesNotLimitHealthChecks() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/actuator/health").with(r -> {
                r.setRemoteAddr("10.0.0.9");
                return r;
            })).andExpect(status().isOk());
        }
    }
}
