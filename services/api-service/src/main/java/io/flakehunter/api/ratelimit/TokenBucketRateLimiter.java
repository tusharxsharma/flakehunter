package io.flakehunter.api.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token-bucket rate limiter keyed by client (API key or IP).
 *
 * <p>Each client has a bucket holding up to {@code capacity} tokens that refills continuously at
 * {@code refillPerSecond}. A request spends one token; an empty bucket means HTTP 429. This allows
 * short bursts (up to capacity) while enforcing a long-run average rate.
 *
 * <p>Refill is computed lazily from elapsed time on each call, so there is no background thread.
 * The clock is injectable so tests can move time deterministically instead of sleeping.
 *
 * <p>Scope note: state is per JVM. With several replicas, each enforces its own limit; a shared
 * limiter would move this state to Redis (e.g. a Lua script doing the same arithmetic atomically).
 */
public class TokenBucketRateLimiter {

    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final long capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(long capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity must be >= 1 and refillPerSecond > 0");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.nanoClock = nanoClock;
    }

    public Decision tryAcquire(String clientKey) {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            evictFullBuckets();
        }
        Bucket bucket = buckets.computeIfAbsent(clientKey, k -> new Bucket(capacity, nanoClock.getAsLong()));
        synchronized (bucket) {
            long now = nanoClock.getAsLong();
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.lastRefillNanos) * refillPerNano);
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new Decision(true, (long) Math.floor(bucket.tokens), 0);
            }
            double nanosUntilToken = (1.0 - bucket.tokens) / refillPerNano;
            long retryAfterSeconds = Math.max(1, (long) Math.ceil(nanosUntilToken / 1_000_000_000.0));
            return new Decision(false, 0, retryAfterSeconds);
        }
    }

    public long capacity() {
        return capacity;
    }

    int trackedClients() {
        return buckets.size();
    }

    private void evictFullBuckets() {
        long now = nanoClock.getAsLong();
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                return b.tokens + (now - b.lastRefillNanos) * refillPerNano >= capacity;
            }
        });
    }

    /** Result of an acquire attempt. */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(long tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}
