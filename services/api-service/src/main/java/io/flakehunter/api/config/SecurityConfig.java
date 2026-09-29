package io.flakehunter.api.config;

import io.flakehunter.api.ratelimit.RateLimitFilter;
import io.flakehunter.api.ratelimit.TokenBucketRateLimiter;
import io.flakehunter.api.repository.ProjectRepository;
import io.flakehunter.api.security.ApiKeyAuthenticationFilter;
import io.flakehunter.api.security.ProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless API security.
 *
 * <p>Reads are public (dashboards and CI jobs need them without secrets); writes that change a
 * project's data require that project's API key. Ownership (a key for project A cannot quarantine
 * tests in project B) is enforced in the service layer.
 */
@Configuration
public class SecurityConfig {

    @Bean
    ProblemWriter problemWriter(JsonMapper jsonMapper) {
        return new ProblemWriter(jsonMapper);
    }

    @Bean
    TokenBucketRateLimiter tokenBucketRateLimiter(FlakeHunterProperties properties) {
        var rateLimit = properties.rateLimit();
        return new TokenBucketRateLimiter(rateLimit.capacity(), rateLimit.refillPerSecond(), System::nanoTime);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProjectRepository projects, ProblemWriter problemWriter,
                                            TokenBucketRateLimiter limiter, FlakeHunterProperties properties)
            throws Exception {
        // Filters are created here, not as @Components, so they run once inside the security chain
        // instead of also being auto-registered with the servlet container.
        var apiKeyFilter = new ApiKeyAuthenticationFilter(projects, problemWriter);

        http
                .csrf(AbstractHttpConfigurer::disable) // no cookies/sessions, so CSRF does not apply
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs").hasRole("PROJECT")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/projects/*/tests/*/quarantine").hasRole("PROJECT")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/*/tests/*/quarantine").hasRole("PROJECT")
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> problemWriter.write(response,
                                HttpStatus.UNAUTHORIZED, "missing_api_key",
                                "This endpoint requires an X-API-Key header", request.getRequestURI()))
                        .accessDeniedHandler((request, response, e) -> problemWriter.write(response,
                                HttpStatus.FORBIDDEN, "forbidden", "Access denied", request.getRequestURI())));

        if (properties.rateLimit().enabled()) {
            http.addFilterBefore(new RateLimitFilter(limiter, problemWriter), ApiKeyAuthenticationFilter.class);
        }
        return http.build();
    }
}
