package io.github.returnaction.fitness.identity.registration.security;

import io.github.returnaction.fitness.identity.registration.exception.RecoveryAccessException;
import io.github.returnaction.fitness.identity.shared.exception.InvalidAccessTokenException;
import io.github.returnaction.fitness.identity.shared.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Проверка доступа применяется только к административному пути восстановления.
 */
@RequiredArgsConstructor
public final class RecoveryAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/v1/admin/identity/registration-operations/";
    private final RecoveryAccessVerifier verifier;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            fail(request, response, 401, "invalid_access_token", "Authentication required.");
            return;
        }
        try {
            var userId = verifier.verify(authorization.substring(7));
            var auth = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority("identity.registration.recover")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (InvalidAccessTokenException exception) {
            fail(request, response, 401, "invalid_access_token", "Authentication required.");
            return;
        } catch (RecoveryAccessException exception) {
            boolean forbidden = "forbidden".equals(exception.code());
            fail(
                    request,
                    response,
                    forbidden ? 403 : 401,
                    exception.code(),
                    forbidden ? "Access denied." : "Authentication required.");
            return;
        } catch (DataAccessException | TransactionException exception) {
            fail(request, response, 503, "identity_unavailable", "Authentication unavailable.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void fail(HttpServletRequest request,
                      HttpServletResponse response,
                      int status,
                      String code,
                      String message) throws IOException {
        Object id = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message
                        + "\",\"details\":[],\"requestId\":\"" + id + "\"}}");
    }
}
