package io.github.returnaction.fitness.accounts.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Настраивает проверку доступа к HTTP API Accounts.
 *
 * <p>Подключает ProfileAuthenticationFilter:
 * <ul>
 *   <li>Извлекает пользователя и роли из Bearer-токена.</li>
 *   <li>Помещает их в SecurityContext.</li>
 * </ul>
 *
 * <p>Подпись токена и активность сессии проверяет Gateway.
 *
 * <p>Правила доступа:
 * <ul>
 *   <li>GET и PUT /api/v1/accounts/me — только для аутентифицированного пользователя.</li>
 *   <li>Перечисленные actuator endpoints — без аутентификации.</li>
 *   <li>Все остальные запросы запрещены.</li>
 * </ul>
 *
 * <p>Стандартные способы входа и хранение аутентификации в HTTP-сессии отключены.
 *
 * <p>Ошибки аутентификации и доступа передаются в SecurityExceptionHandler.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityExceptionHandler securityExceptionHandler) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(securityExceptionHandler)
                .accessDeniedHandler(securityExceptionHandler))
            .addFilterBefore(new ProfileAuthenticationFilter(securityExceptionHandler),
                UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(authorization -> authorization
                .requestMatchers("/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/actuator/prometheus")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/accounts/me")
                .authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/me")
                .authenticated()
                .anyRequest()
                .denyAll())
            .build();
    }
}
