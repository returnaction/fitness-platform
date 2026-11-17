package io.github.returnaction.fitness.coins.shared.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;


/**
 * Создаёт контекст пользователя для HTTP API Coins из JWT, предварительно проверенного Gateway.
 * Извлекает userId из sub и роли из claims, затем помещает их в SecurityContext на время запроса.
 * Сам не проверяет подпись, срок действия, issuer и audience JWT.
 * Такая схема требует исключить прямой доступ к API Coins в обход доверенного Gateway;
 * этот фильтр сам такое ограничение не обеспечивает.
 */
@RequiredArgsConstructor
public class CoinsAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> KNOWN_ROLES = Set.of("USER", "MODERATOR", "ADMIN", "SUPER_ADMIN");
    private final SecurityExceptionHandler securityExceptionHandler;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Шаг 1-A: Spring решает, выполнять ли наш фильтр. RequestIdFilter уже назначил ID запроса.
        // false → вызывается doFilterInternal (1-B); true → наш фильтр пропускается, цепочка идёт дальше.

        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Фильтр извлекает sub для всех путей Coins; доступ к операциям
        // ограничивает SecurityConfiguration.
        return !path.startsWith("/api/v1/coins/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        UsernamePasswordAuthenticationToken authentication;

        try {
            // Шаг 1-B: читаем пользователя и роли. Подробности — в readAuthentication ниже.
            authentication = readAuthentication(request);
        } catch (BadCredentialsException exception) {
            // Ветка E-A: нет подходящего контекста пользователя → commence(), ответ 401 и остановка.
            SecurityContextHolder.clearContext();
            securityExceptionHandler.commence(request, response, exception);
            return;
        } catch (ParseException | IllegalArgumentException _) {
            // Ветка E-A: токен/UUID не удалось разобрать → тот же путь ошибки без вызова контроллера.
            SecurityContextHolder.clearContext();
            securityExceptionHandler.commence(request, response,
                    new BadCredentialsException("Invalid authentication context."));
            return;
        }

        // Шаг 1-C: сохраняем пользователя и роли в SecurityContext для дальнейшей обработки запроса.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        try {
            // Шаг 1-D: передаём запрос оставшимся фильтрам, включая проверку доступа (2-A).
            // Этот вызов вернётся после дальнейшей обработки: interceptors, контроллера или ответа с ошибкой.
            filterChain.doFilter(request, response);
        } finally {
            // Шаг 4-A: обработка ниже по цепочке закончилась. Убираем пользователя из текущего потока,
            // чтобы его контекст не остался для следующего запроса. Выполняется и при исключении.
            SecurityContextHolder.clearContext();
        }

    }

    private UsernamePasswordAuthenticationToken readAuthentication(HttpServletRequest request) throws ParseException {

        // Шаг 1-B.1: требуем заголовок Authorization с непустым Bearer-токеном.
        String authorization = request.getHeader("Authorization");

        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || authorization.length() <= 7) {
            throw new BadCredentialsException("Invalid authentication context.");
        }

        // Шаг 1-B.2: разбираем claims. Проверки подписи здесь нет — ожидаем проверенный Gateway токен.
        String token = authorization.substring(7);
        var claims = SignedJWT.parse(token).getJWTClaimsSet();

        String subject = claims.getSubject();
        if (subject == null) {
            throw new BadCredentialsException("Invalid authentication context.");
        }

        // Шаг 1-B.3: sub должен быть UUID пользователя в полном формате.
        UUID userId = UUID.fromString(subject);

        if (!userId.toString().equalsIgnoreCase(subject)) {
            throw new BadCredentialsException("Invalid authentication context.");
        }

        // Шаг 1-B.4: читаем роли и собираем Authentication; затем возвращаемся к шагу 1-C.
        var authorities = readAuthorities(claims);

        return new UsernamePasswordAuthenticationToken(userId, null, authorities);
    }

    private List<GrantedAuthority> readAuthorities(JWTClaimsSet claims) {
        // Подшаг 1-B.4: отсутствие roles допустимо. Если поле есть — ожидаем список строк.
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
                // Известные роли переводим в формат Spring Security; неизвестные не добавляем.
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }

        return authorities;
    }
}
