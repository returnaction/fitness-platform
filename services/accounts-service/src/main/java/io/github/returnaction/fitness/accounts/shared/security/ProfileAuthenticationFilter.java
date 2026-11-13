package io.github.returnaction.fitness.accounts.shared.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Extracts the user context already checked by Gateway; does not verify the
 * HTTP caller or JWT signature.
 */
@RequiredArgsConstructor
public class ProfileAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> KNOWN_ROLES = Set.of("USER", "MODERATOR", "ADMIN", "SUPER_ADMIN");
    private final SecurityExceptionHandler securityExceptionHandler;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        boolean profilePath = "/api/v1/accounts/me".equals(path);
        boolean profileMethod = "GET".equals(request.getMethod()) || "PUT".equals(request.getMethod());

        return !(profilePath && profileMethod);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        UsernamePasswordAuthenticationToken authentication;

        try {
            authentication = readAuthentication(request);
        } catch (BadCredentialsException exception) {
            SecurityContextHolder.clearContext();
            securityExceptionHandler.commence(request, response, exception);
            return;
        } catch (ParseException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
            securityExceptionHandler.commence(request,
                response,
                new BadCredentialsException("Invalid authentication context."));
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

    private UsernamePasswordAuthenticationToken readAuthentication(HttpServletRequest request) throws ParseException {

        String authorization = request.getHeader("Authorization");

        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
            || authorization.length() <= 7) {
            throw new BadCredentialsException("Invalid authentication context.");
        }

        String token = authorization.substring(7);
        var claims = SignedJWT.parse(token).getJWTClaimsSet();

        String subject = claims.getSubject();
        if (subject == null) {
            throw new BadCredentialsException("Invalid authentication context.");
        }

        UUID userId = UUID.fromString(subject);

        if (!userId.toString().equalsIgnoreCase(subject)) {
            throw new BadCredentialsException("Invalid authentication context.");
        }

        var authorities = readAuthorities(claims);

        return new UsernamePasswordAuthenticationToken(userId, null, authorities);
    }

    private List<GrantedAuthority> readAuthorities(JWTClaimsSet claims) {
        if (!claims.getClaims().containsKey("roles")) {
            return List.of();
        }

        Object rawRoles = claims.getClaim("roles");

        if (!(rawRoles instanceof List<?> roles)) {
            throw new BadCredentialsException("Invalid authentication context.");
        }

        List<GrantedAuthority> authorities = new ArrayList<>();

        for (Object rawRole : roles) {
            if (!(rawRole instanceof String role)) {
                throw new BadCredentialsException("Invalid authentication context.");
            }

            if (KNOWN_ROLES.contains(role)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }

        return authorities;
    }
}
