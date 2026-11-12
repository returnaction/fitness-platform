package io.github.returnaction.fitness.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;

/**
 * Keeps the scaffold closed until the route and authentication chain is
 * implemented.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            GatewayErrorWriter writer,
                                            io.github.returnaction.fitness.gateway.readiness.ManagementPort management) throws Exception {
        return http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(management::matches)
                .permitAll()
                .anyRequest()
                .denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writer.write(response, 401,
                                GatewayErrorCode.INVALID_ACCESS_TOKEN, RequestIdResolver.getOrCreate(request)))
                        .accessDeniedHandler((request, response, exception) -> writer.write(response, 403,
                                GatewayErrorCode.ACCESS_DENIED, RequestIdResolver.getOrCreate(request))))
                .build();
    }
}
