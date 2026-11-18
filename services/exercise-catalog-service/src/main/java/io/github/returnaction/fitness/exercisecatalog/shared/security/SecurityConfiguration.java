package io.github.returnaction.fitness.exercisecatalog.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityExceptionHandler securityExceptionHandler) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers.cacheControl(cache -> cache.disable()))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(sessions ->
                sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(securityExceptionHandler)
                .accessDeniedHandler(securityExceptionHandler)
            )
            .addFilterBefore(
                new CatalogAuthenticationFilter(securityExceptionHandler),
                UsernamePasswordAuthenticationFilter.class
            )
            .authorizeHttpRequests(authorization -> authorization
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/actuator/prometheus"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/exercise-catalog").authenticated()
                .anyRequest().denyAll()
            )
            .build();
    }
}
