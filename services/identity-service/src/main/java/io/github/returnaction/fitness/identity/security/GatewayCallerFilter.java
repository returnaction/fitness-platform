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
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

/**
 * Authorizes the TLS peer without granting user authentication.
 */
public final class GatewayCallerFilter extends OncePerRequestFilter {

    private static final String GATEWAY = "spiffe://fitness.local/gateway-service";
    private static final Set<String> AUTH_PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/logout-all");
    private static final Set<String> MANAGEMENT_PATHS = Set.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/info",
            "/actuator/prometheus");
    private final Environment environment;
    private final ObjectWriter errorWriter;

    public GatewayCallerFilter(Environment environment, JsonMapper mapper) {
        this.environment = environment;
        this.errorWriter = mapper.writerFor(ApiErrorResponse.class);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean allowed;
        if (isManagementPort(request)) {
            allowed = "GET".equals(request.getMethod()) && MANAGEMENT_PATHS.contains(path);
        } else {
            boolean operation = ("POST".equals(request.getMethod()) && AUTH_PATHS.contains(path))
                    || ("GET".equals(request.getMethod()) && "/.well-known/jwks.json".equals(path));
            allowed = operation && isGateway(request);
        }
        if (!allowed) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            errorWriter.writeValue(response.getOutputStream(), new ApiErrorResponse(new ApiErrorResponse.Error(
                    "forbidden", "Access denied.", List.of(),
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

    private boolean isGateway(HttpServletRequest request) {
        if (!request.isSecure()
                || !(request.getAttribute("jakarta.servlet.request.X509Certificate") instanceof X509Certificate[] chain)
                || chain.length == 0) {
            return false;
        }
        try {
            var names = chain[0].getSubjectAlternativeNames();
            return names != null && names.stream()
                    .anyMatch(name -> name.size() >= 2
                            && Integer.valueOf(6).equals(name.get(0)) && GATEWAY.equals(name.get(1)));
        } catch (CertificateParsingException invalid) {
            return false;
        }
    }
}
