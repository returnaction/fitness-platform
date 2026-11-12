package io.github.returnaction.fitness.gateway.routing;

import io.github.returnaction.fitness.gateway.readiness.ManagementPort;

import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults;

import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public final class RouteValidationFilter extends OncePerRequestFilter {

    private final GatewayErrorWriter errorWriter;
    private final ManagementPort management;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return management.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        List<String> allowedMethods = allowedMethods(request);
        if (allowedMethods.isEmpty()) {
            errorWriter.write(response, HttpServletResponse.SC_NOT_FOUND,
                    GatewayErrorCode.NOT_FOUND, RequestIdResolver.getOrCreate(request));
            return;
        }
        if (!allowedMethods.contains(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, String.join(", ", allowedMethods));
            errorWriter.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    GatewayErrorCode.METHOD_NOT_ALLOWED, RequestIdResolver.getOrCreate(request));
            return;
        }
        if (request.getQueryString() != null) {
            errorWriter.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    GatewayErrorCode.INVALID_REQUEST, RequestIdResolver.getOrCreate(request));
            return;
        }
        chain.doFilter(request, response);
    }

    private static List<String> allowedMethods(HttpServletRequest request) {
        var paths = withDefaults();
        return GatewayRouteCatalog.routes()
                .stream()
                .filter(definition -> paths.matcher(definition.path()).matches(request))
                .map(definition -> definition.method().name())
                .distinct()
                .sorted()
                .toList();
    }
}
