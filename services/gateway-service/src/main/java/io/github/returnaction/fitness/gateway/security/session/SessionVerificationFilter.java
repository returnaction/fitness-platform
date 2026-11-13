package io.github.returnaction.fitness.gateway.security.session;

import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import io.github.returnaction.fitness.gateway.error.SecurityErrorHandler;
import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadlineFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public final class SessionVerificationFilter extends OncePerRequestFilter {

    private final RouteAccessPolicy accessPolicy;
    private final SessionVerificationClient sessionClient;
    private final SecurityErrorHandler securityErrors;
    private final GatewayErrorWriter errorWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !accessPolicy.requiresSessionVerification(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth) || !jwtAuth.isAuthenticated()) {
            rejectAuthentication(request, response);
            return;
        }
        UUID userId;
        UUID sessionId;
        var token = jwtAuth.getToken();
        String subject = token.getSubject();
        String sid = token.getClaimAsString("sid");
        if (subject == null || sid == null) {
            rejectAuthentication(request, response);
            return;
        }
        try {
            userId = UUID.fromString(subject);
            sessionId = UUID.fromString(sid);
        } catch (IllegalArgumentException exception) {
            rejectAuthentication(request, response);
            return;
        }
        UUID requestId = requireRequestId(request);
        final boolean active;
        try {
            active = sessionClient.isActive(userId, sessionId, requestId,
                    RequestDeadlineFilter.requireDeadline(request));
        } catch (SessionVerificationUnavailableException exception) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    GatewayErrorCode.IDENTITY_UNAVAILABLE, requestId.toString());
            return;
        }
        if (!active) {
            rejectAuthentication(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void rejectAuthentication(HttpServletRequest request,
        HttpServletResponse response) throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        securityErrors.commence(request, response, new BadCredentialsException("Invalid access token"));
    }

    private static UUID requireRequestId(HttpServletRequest request) {
        Object value = request.getAttribute("request_id");
        if (!(value instanceof String text)) {
            throw new IllegalStateException("RequestIdFilter must set request_id as a string before security filters");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("RequestIdFilter must provide a valid request_id", exception);
        }
    }
}
