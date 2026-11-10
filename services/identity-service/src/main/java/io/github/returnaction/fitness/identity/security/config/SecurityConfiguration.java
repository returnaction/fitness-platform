package io.github.returnaction.fitness.identity.security.config;

import io.github.returnaction.fitness.identity.registration.security.RecoveryAccessVerifier;
import io.github.returnaction.fitness.identity.registration.security.RecoveryAuthenticationFilter;
import io.github.returnaction.fitness.identity.registration.web.RegistrationClientAddress;
import io.github.returnaction.fitness.identity.registration.web.RegistrationRateLimiter;
import io.github.returnaction.fitness.identity.security.jwt.AccessTokenVerifier;
import io.github.returnaction.fitness.identity.security.jwt.LogoutAllAuthenticationFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, RecoveryAccessVerifier verifier,
                                            AccessTokenVerifier accessVerifier,
                                            RegistrationClientAddress clientAddress,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
                                            @Value("${identity.registration.logout-all-rate-capacity}") long capacity,
                                            @Value("${identity.registration.rate-limit.period-seconds}") long period,
                                            @Value("${identity.registration.rate-limit.cache-size}") long cacheSize,
                                            @Value("${identity.registration.rate-limit.cache-expiry-seconds}") long expiry)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new RecoveryAuthenticationFilter(verifier), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new LogoutAllAuthenticationFilter(accessVerifier, clientAddress,
                        new RegistrationRateLimiter(capacity, period, cacheSize, expiry), exceptionResolver),
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
                        .hasAuthority("identity.registration.recover")
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
