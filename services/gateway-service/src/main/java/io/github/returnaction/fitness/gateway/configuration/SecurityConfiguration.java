package io.github.returnaction.fitness.gateway.configuration;

import io.github.returnaction.fitness.gateway.security.AccessTokens;
import io.github.returnaction.fitness.gateway.security.RequestLimits;
import io.github.returnaction.fitness.gateway.security.SessionAccess;
import io.github.returnaction.fitness.gateway.web.ApiErrors;
import io.github.returnaction.fitness.gateway.web.GatewayFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer firewallErrors(ApiErrors errors) {
        return web -> web.requestRejectedHandler((request, response, exception) -> errors.write(response, 400,
                "invalid_request", java.util.UUID.randomUUID().toString()));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, GatewayProperties settings, AccessTokens tokens,
                                 SessionAccess sessions, RequestLimits limits, ApiErrors errors,
                                 Environment environment)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new GatewayFilter(settings, tokens, sessions, limits, errors, environment),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(request -> GatewayFilter.management(request, environment))
                        .permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout")
                        .permitAll()
                        .requestMatchers("/api/v1/auth/logout-all")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
