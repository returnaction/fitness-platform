package io.github.returnaction.fitness.exercisecatalog.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                .permitAll()
                .requestMatchers("/api/v1/exercise-categories")
                .permitAll()
                .requestMatchers("/api/v1/exercises")
                .permitAll()
                .requestMatchers("/api/v1/exercises/*")
                .permitAll()
                .anyRequest()
                .denyAll())
                .build();
    }
}
