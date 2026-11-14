package io.github.returnaction.fitness.gateway.readiness;

import java.io.IOException;
import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import io.github.returnaction.fitness.gateway.security.jwks.JwksInitializer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public final class ReadinessGateFilter extends OncePerRequestFilter {

    private final JwksInitializer keys;
    private final ManagementPort management;
    private final GatewayErrorWriter errors;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        if (!management.matches(request) && !keys.ready()) {
            errors.write(response, 503, GatewayErrorCode.IDENTITY_UNAVAILABLE, RequestIdResolver.getOrCreate(request));
            return;
        }
        chain.doFilter(request, response);
    }
}
