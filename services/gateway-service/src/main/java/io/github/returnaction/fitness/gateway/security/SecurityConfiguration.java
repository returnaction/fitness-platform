package io.github.returnaction.fitness.gateway.security;

import io.github.returnaction.fitness.gateway.security.session.SessionVerificationFilter;

import java.util.List;
import jakarta.servlet.DispatcherType;
import io.github.returnaction.fitness.gateway.error.SecurityErrorHandler;
import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import io.github.returnaction.fitness.gateway.readiness.ManagementPort;
import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import io.github.returnaction.fitness.gateway.routing.RouteValidationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    WebSecurityCustomizer firewallErrors(GatewayErrorWriter writer) {
        return web -> web.requestRejectedHandler((request, response, failure) -> writer.write(response, 400,
                GatewayErrorCode.INVALID_REQUEST, RequestIdResolver.getOrCreate(request)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityErrorHandler errors,
                                            ManagementPort management,
                                            RouteValidationFilter routeValidation,
                                            RouteAccessPolicy policy,
                                            JwtDecoder decoder,
                                            SessionVerificationFilter sessionFilter) throws Exception {
        var bearer = new DefaultBearerTokenResolver();
        var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(routeValidation, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(sessionFilter, BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(
                                request -> management.matches(request) || policy.publicRequests().matches(request)
                                        ? null
                                        : bearer.resolve(request))
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authentication))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .authorizeHttpRequests(authorize -> authorize.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(management::matches)
                        .permitAll()
                        .requestMatchers(policy.publicRequests())
                        .permitAll()
                        .requestMatchers(policy.protectedRequests())
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(
                        exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .build();
    }
}
