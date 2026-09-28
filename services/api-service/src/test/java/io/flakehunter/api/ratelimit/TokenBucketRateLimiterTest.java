package io.flakehunter.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Uses a fake clock, so time-dependent behaviour is tested instantly and deterministically (no sleeps). */
class TokenBucketRateLimiterTest {

    private static final long ONE_SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong(0);
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1.0, clock::get);

    @Test
    void allowsABurstUpToCapacity() {
        assertThat(limiter.tryAcquire("a").remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire("a").remaining()).isEqualTo(1);
        assertThat(limiter.tryAcquire("a").remaining()).isZero();
    }

    @Test
    void rejectsWhenEmptyAndSaysWhenToRetry() {
        exhaust("a");

        var decision = limiter.tryAcquire("a");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTime() {
        exhaust("a");

        clock.addAndGet(ONE_SECOND);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void neverRefillsBeyondCapacity() {
        clock.addAndGet(100 * ONE_SECOND);

        exhaust("a");

        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void clientsHaveIndependentBuckets() {
        exhaust("a");

        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
        assertThat(limiter.trackedClients()).isEqualTo(2);
    }

    @Test
    void retryAfterReflectsSlowRefillRates() {
        var slow = new TokenBucketRateLimiter(1, 0.1, clock::get); // one token per 10s
        slow.tryAcquire("a");

        assertThat(slow.tryAcquire("a").retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1.0, clock::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, 0, clock::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void exhaust(String client) {
        for (int i = 0; i < limiter.capacity(); i++) {
            assertThat(limiter.tryAcquire(client).allowed()).isTrue();
        }
    }
}
