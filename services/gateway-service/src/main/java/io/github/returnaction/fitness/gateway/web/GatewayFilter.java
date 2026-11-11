package io.github.returnaction.fitness.gateway.web;

import io.github.returnaction.fitness.gateway.configuration.GatewayProperties;
import io.github.returnaction.fitness.gateway.security.AccessTokenVerifier;
import io.github.returnaction.fitness.gateway.exception.GatewayException;
import io.github.returnaction.fitness.gateway.security.RequestRateLimiter;
import io.github.returnaction.fitness.gateway.security.SessionVerificationClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class GatewayFilter extends OncePerRequestFilter {
    public static final Set<String> PATHS = Set.of("/api/v1/auth/register", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/logout-all");
    private final GatewayProperties settings;
    private final AccessTokenVerifier tokens;
    private final SessionVerificationClient sessions;
    private final RequestRateLimiter limits;
    private final ApiErrors errors;
    private final Environment environment;

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Stateless error writer is an injected shared component")
    public GatewayFilter(GatewayProperties settings,
                         AccessTokenVerifier tokens,
                         SessionVerificationClient sessions,
                         RequestRateLimiter limits,
                         ApiErrors errors,
                         Environment environment) {
        this.settings = settings;
        this.tokens = tokens;
        this.sessions = sessions;
        this.limits = limits;
        this.errors = errors;
        this.environment = environment;
    }

    public static boolean management(HttpServletRequest request, Environment environment) {
        return request.getLocalPort() == environment.getProperty("local.management.port", Integer.class, -1)
                && "GET".equals(request.getMethod()) && Set.of("/actuator/health", "/actuator/health/liveness",
                        "/actuator/health/readiness", "/actuator/info", "/actuator/prometheus")
                        .contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = requestId(request.getHeader("X-Request-Id"));
        request.setAttribute("request_id", requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("Cache-Control", "no-store");
        MDC.put("request_id", requestId);
        try {
            if (management(request, environment)) {
                chain.doFilter(request, response);
                return;
            }
            limits.check(request.getRemoteAddr());
            if (!PATHS.contains(request.getRequestURI()))
                throw new GatewayException(404, "not_found");
            if (!"POST".equals(request.getMethod())) {
                response.setHeader("Allow", "POST");
                throw new GatewayException(405, "method_not_allowed");
            }
            if (request.getQueryString() != null)
                throw new GatewayException(400, "invalid_request");
            if (request.getContentLengthLong() > settings.maxBodyBytes())
                throw new GatewayException(413, "payload_too_large");
            byte[] body = request.getInputStream().readNBytes(settings.maxBodyBytes() + 1);
            if (body.length > settings.maxBodyBytes())
                throw new GatewayException(413, "payload_too_large");
            if (request.getRequestURI().equals("/api/v1/auth/logout-all")) {
                var headers = Collections.list(request.getHeaders("Authorization"));
                if (headers.size() != 1 || !headers.getFirst().startsWith("Bearer "))
                    throw GatewayException.invalidAccessToken();
                var principal = tokens.verify(headers.getFirst().substring(7));
                sessions.check(principal, requestId);
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
            }
            chain.doFilter(new BufferedRequest(request, body), response);
        } catch (GatewayException failure) {
            if (failure.status() == 429)
                response.setHeader("Retry-After", Integer.toString(settings.ratePeriodSeconds()));
            errors.write(response, failure.status(), failure.code(), requestId);
        } finally {
            MDC.remove("request_id");
        }
    }

    private static String requestId(String value) {
        try {
            if (value != null && UUID.fromString(value).toString().equals(value))
                return value;
        } catch (IllegalArgumentException ignored) {
        }
        return UUID.randomUUID().toString();
    }
}
