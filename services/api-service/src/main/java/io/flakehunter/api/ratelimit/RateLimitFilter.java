package io.flakehunter.api.ratelimit;

import io.flakehunter.api.security.ApiKeyAuthenticationFilter;
import io.flakehunter.api.security.ApiKeys;
import io.flakehunter.api.security.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link TokenBucketRateLimiter} to {@code /api/**}. Clients are identified by API key
 * when present (fair per project, even behind a shared NAT), otherwise by IP address.
 * Standard {@code RateLimit-*} and {@code Retry-After} headers tell clients how to back off.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter limiter;
    private final ProblemWriter problemWriter;

    public RateLimitFilter(TokenBucketRateLimiter limiter, ProblemWriter problemWriter) {
        this.limiter = limiter;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(ApiKeyAuthenticationFilter.HEADER);
        // Never keep raw secrets in memory maps: key the bucket by the key's hash.
        String clientKey = apiKey != null && !apiKey.isBlank()
                ? "key:" + ApiKeys.hash(apiKey.trim())
                : "ip:" + request.getRemoteAddr();

        TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(clientKey);
        response.setHeader("RateLimit-Limit", String.valueOf(limiter.capacity()));
        response.setHeader("RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            problemWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Too many requests; retry after " + decision.retryAfterSeconds() + "s", request.getRequestURI());
            return;
        }
        chain.doFilter(request, response);
    }
}
