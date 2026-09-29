package io.flakehunter.api.security;

import io.flakehunter.api.repository.ProjectRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests that carry an {@code X-API-Key} header.
 *
 * <ul>
 *   <li>No header: request continues anonymously; protected endpoints then answer 401.</li>
 *   <li>Unknown key: rejected immediately with 401 (never silently downgraded to anonymous).</li>
 *   <li>Valid key: the request runs as {@link ProjectPrincipal} with role PROJECT.</li>
 * </ul>
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ProjectRepository projects;
    private final ProblemWriter problemWriter;

    public ApiKeyAuthenticationFilter(ProjectRepository projects, ProblemWriter problemWriter) {
        this.projects = projects;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        var project = projects.findByApiKeyHash(ApiKeys.hash(apiKey.trim()));
        if (project.isEmpty()) {
            problemWriter.write(response, HttpStatus.UNAUTHORIZED, "invalid_api_key",
                    "The API key is not valid", request.getRequestURI());
            return;
        }

        var principal = new ProjectPrincipal(project.get().getId(), project.get().getName());
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_PROJECT")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
