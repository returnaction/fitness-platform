package io.github.returnaction.fitness.coins.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;


/**
 * Карта обработки обычного запроса Coins (служебные фильтры Spring здесь не перечислены):
 * RequestIdFilter → 1-A..1-D CoinsAuthenticationFilter → 2-A проверка доступа Spring Security
 * → 3-A NoStoreResponseInterceptor → 3-B NoQueryParametersInterceptor → контроллер
 * → 4-A очистка контекста в CoinsAuthenticationFilter.
 * При ошибке аутентификации/доступа выполняется ветка E в SecurityExceptionHandler.
 * Эта конфигурация собирает цепочку при запуске приложения, а не обрабатывает каждый запрос сама.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityExceptionHandler securityExceptionHandler) {
        // Настройка A: API работает с Bearer из заголовка, без формы входа и HTTP-сессии.
        return http.csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Настройка B: ошибки security направляем в ветку E, а не в контроллер.
            .exceptionHandling(errors -> errors.authenticationEntryPoint(securityExceptionHandler)
                .accessDeniedHandler(securityExceptionHandler))
            // Настройка C: подключаем шаги 1-A..1-D до проверки доступа.
            .addFilterBefore(new CoinsAuthenticationFilter(securityExceptionHandler),
                UsernamePasswordAuthenticationFilter.class)
            // Шаг 2-A при запросе: Spring Security применяет эти правила к пользователю из контекста.
            // Если доступ разрешён, идём к MVC-interceptors (шаг 3-A); иначе — ветка E.
            .authorizeHttpRequests(authorization -> authorization
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/actuator/prometheus"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/coins/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/coins/daily-reward/status").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/coins/daily-reward/claims").authenticated()
                .anyRequest()
                .denyAll())
            .build();
    }
}
