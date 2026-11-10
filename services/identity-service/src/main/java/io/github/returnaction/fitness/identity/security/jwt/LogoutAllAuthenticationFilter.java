package io.github.returnaction.fitness.identity.security.jwt;

import io.github.returnaction.fitness.identity.registration.exception.RegistrationRateLimitException;
import io.github.returnaction.fitness.identity.registration.web.RegistrationClientAddress;
import io.github.returnaction.fitness.identity.registration.web.RegistrationRateLimiter;
import io.github.returnaction.fitness.identity.shared.exception.InvalidAccessTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Проверяет JWT до контроллера logout-all; сессия повторно проверяется в
 * транзакции.
 */
@RequiredArgsConstructor
public final class LogoutAllAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenVerifier verifier;
    private final RegistrationClientAddress clientAddress;
    private final RegistrationRateLimiter limiter;
    private final HandlerExceptionResolver exceptionResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !"/api/v1/auth/logout-all".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String accept = request.getHeader("Accept");
        try {
            if (accept != null && MediaType.parseMediaTypes(accept)
                    .stream()
                    .noneMatch(
                            type -> type.getQualityValue() > 0 && type.isCompatibleWith(MediaType.APPLICATION_JSON))) {
                response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
                return;
            }
        } catch (IllegalArgumentException invalid) {
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            return;
        }
        try {
            limiter.check(clientAddress.resolve(request));
        } catch (RegistrationRateLimitException limited) {
            handleError(request, response, limited);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            handleError(request, response, new InvalidAccessTokenException());
            return;
        }
        try {
            var identity = verifier.verify(authorization.substring(7));
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new UsernamePasswordAuthenticationToken(identity, null, List.of()));
        } catch (InvalidAccessTokenException invalid) {
            handleError(request, response, invalid);
            return;
        }
        chain.doFilter(request, response);
    }

    private void handleError(HttpServletRequest request, HttpServletResponse response, Exception exception) {
        if (exceptionResolver.resolveException(request, response, null, exception) == null && !response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
