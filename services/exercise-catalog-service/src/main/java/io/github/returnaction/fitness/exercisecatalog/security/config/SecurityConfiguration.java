package io.github.returnaction.fitness.exercisecatalog.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                .permitAll()
                // Пользовательский JWT проверяет Gateway; Catalog не повторяет эту
                // проверку. Механизм допуска только Gateway фиксируется в D003/T013.
                .requestMatchers(HttpMethod.GET, "/api/v1/exercise-categories", "/api/v1/exercises",
                    "/api/v1/exercises/**")
                .permitAll()
                .anyRequest()
                .denyAll()).build();
    }
}
