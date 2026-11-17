package io.github.returnaction.fitness.challenge.shared.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Извлекает пользователя и роли из forwarded Bearer JWT для HTTP API Challenge.
 *
 * <p>Gateway уже проверил подпись, срок действия токена и активность сессии,
 * поэтому фильтр проверяет только формат: UUID в {@code sub} и известные роли
 * в {@code roles}. Отсутствующий или некорректный токен не создаёт
 * аутентификацию; решение об отказе принимает security chain.</p>
 *
 * <p>Контекст очищается после каждого запроса, в том числе при ошибке.</p>
 */
public class ChallengeAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> KNOWN_ROLES = Set.of("USER", "MODERATOR", "ADMIN", "SUPER_ADMIN");
    private static final List<String> API_PREFIXES = List.of(
            "/api/v1/challenges",
            "/api/v1/challenge-operations");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return API_PREFIXES.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        UsernamePasswordAuthenticationToken authentication = readAuthentication(request);
        if (authentication == null) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UsernamePasswordAuthenticationToken readAuthentication(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || authorization.length() <= 7) {
            return null;
        }

        try {
            JWTClaimsSet claims = SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet();
            String subject = claims.getSubject();
            if (subject == null) {
                return null;
            }
            UUID userId = UUID.fromString(subject);
            if (!userId.toString().equalsIgnoreCase(subject)) {
                return null;
            }
            return new UsernamePasswordAuthenticationToken(userId, null, readAuthorities(claims));
        } catch (ParseException | IllegalArgumentException _) {
            return null;
        }
    }

    private List<GrantedAuthority> readAuthorities(JWTClaimsSet claims) {
        if (!claims.getClaims().containsKey("roles")) {
            return List.of();
        }
        Object rawRoles = claims.getClaim("roles");
        if (!(rawRoles instanceof List<?> roles)) {
            throw new IllegalArgumentException("Invalid roles claim");
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Object rawRole : roles) {
            if (!(rawRole instanceof String role)) {
                throw new IllegalArgumentException("Invalid role value");
            }
            if (KNOWN_ROLES.contains(role)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return authorities;
    }
}
