package io.github.returnaction.fitness.identity.security;

import io.github.returnaction.fitness.identity.shared.web.ApiErrorResponse;
import io.github.returnaction.fitness.identity.shared.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Separates API and management operations without granting user authentication.
 */
public final class HttpOperationFilter extends OncePerRequestFilter {

    private static final Set<String> AUTH_PATHS = Set.of("/api/v1/auth/register", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/logout-all");
    private static final Set<String> MANAGEMENT_PATHS = Set.of("/actuator/health", "/actuator/health/liveness",
            "/actuator/health/readiness", "/actuator/info", "/actuator/prometheus");
    private final Environment environment;
    private final ObjectWriter errorWriter;

    public HttpOperationFilter(Environment environment, JsonMapper mapper) {
        this.environment = environment;
        this.errorWriter = mapper.writerFor(ApiErrorResponse.class);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean allowed;
        if (isManagementPort(request)) {
            allowed = "GET".equals(request.getMethod()) && MANAGEMENT_PATHS.contains(path);
        } else {
            boolean operation = ("POST".equals(request.getMethod()) && AUTH_PATHS.contains(path))
                    || ("GET".equals(request.getMethod()) && "/.well-known/jwks.json".equals(path));
            allowed = operation;
        }
        if (!allowed) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            errorWriter.writeValue(response.getOutputStream(),
                    new ApiErrorResponse(new ApiErrorResponse.Error("forbidden", "Access denied.", List.of(),
                            (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isManagementPort(HttpServletRequest request) {
        Integer management = environment.getProperty("local.management.port", Integer.class);
        Integer application = environment.getProperty("local.server.port", Integer.class);
        return management != null && management > 0 && !management.equals(application)
                && request.getLocalPort() == management;
    }

}
