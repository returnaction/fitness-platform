package io.github.returnaction.fitness.coins.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;


@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityExceptionHandler securityExceptionHandler) {
        return http.csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(securityExceptionHandler)
                .accessDeniedHandler(securityExceptionHandler))
            .addFilterBefore(new WalletAuthenticationFilter(securityExceptionHandler),
                UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(authorization -> authorization
                .requestMatchers("/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/actuator/prometheus")
                .permitAll()
                .requestMatchers(HttpMethod.GET,
                 "/api/v1/coins/me")
                .authenticated()
                .requestMatchers(HttpMethod.GET,
                 "/api/v1/coins/daily-reward/status")
                .authenticated()
                .requestMatchers(HttpMethod.POST,
                 "/api/v1/coins/daily-reward/claim")
                .authenticated()
                .anyRequest()
                .denyAll())
            .build();
    }
}
