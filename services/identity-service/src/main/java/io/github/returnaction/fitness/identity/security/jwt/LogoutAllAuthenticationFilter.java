package io.github.returnaction.fitness.identity.security.jwt;

import io.github.returnaction.fitness.identity.shared.exception.InvalidAccessTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.text.ParseException;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Извлекает пользователя из JWT, уже проверенного Gateway. Локальный HTTP
 * caller считается доверенным без проверки его личности.
 */
@RequiredArgsConstructor
public final class LogoutAllAuthenticationFilter extends OncePerRequestFilter {

    private final HandlerExceptionResolver exceptionResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !"/api/v1/auth/logout-all".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
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
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            handleError(request, response, new InvalidAccessTokenException());
            return;
        }
        UUID userId;
        try {
            userId = readUserId(authorization.substring(7));
        } catch (InvalidAccessTokenException invalid) {
            handleError(request, response, invalid);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static UUID readUserId(String token) {
        try {
            String subject = SignedJWT.parse(token).getJWTClaimsSet().getSubject();
            if (subject == null)
                throw new InvalidAccessTokenException();
            UUID userId = UUID.fromString(subject);
            if (!userId.toString().equals(subject))
                throw new InvalidAccessTokenException();
            return userId;
        } catch (ParseException | IllegalArgumentException invalid) {
            throw new InvalidAccessTokenException();
        }
    }

    private void handleError(HttpServletRequest request, HttpServletResponse response, Exception exception) {
        if (exceptionResolver.resolveException(request, response, null, exception) == null && !response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
