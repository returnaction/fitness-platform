package io.github.returnaction.fitness.identity.security.config;

import io.github.returnaction.fitness.identity.security.HttpOperationFilter;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;
import io.github.returnaction.fitness.identity.security.jwt.AccessTokenVerifier;
import io.github.returnaction.fitness.identity.security.jwt.LogoutAllAuthenticationFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            Environment environment,
                                            JsonMapper mapper,
                                            AccessTokenVerifier accessVerifier,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
        throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new HttpOperationFilter(environment, mapper),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new LogoutAllAuthenticationFilter(accessVerifier, exceptionResolver),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authorization -> authorization
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout-all")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/identity/registration-operations/**")
                        .denyAll()
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
