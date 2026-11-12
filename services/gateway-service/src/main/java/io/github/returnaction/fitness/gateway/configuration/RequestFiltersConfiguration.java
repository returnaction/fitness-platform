package io.github.returnaction.fitness.gateway.configuration;

import io.github.returnaction.fitness.gateway.readiness.ReadinessGateFilter;
import io.github.returnaction.fitness.gateway.routing.RouteValidationFilter;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationFilter;

import io.github.returnaction.fitness.gateway.request.RequestIdFilter;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadlineFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class RequestFiltersConfiguration {

    @Bean
    FilterRegistrationBean<SessionVerificationFilter> sessionRegistration(SessionVerificationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RouteValidationFilter> routeValidationRegistration(RouteValidationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ReadinessGateFilter> readinessRegistration(ReadinessGateFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestDeadlineFilter> deadlineRegistration(RequestDeadlineFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdRegistration(RequestIdFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        return registration;
    }
}
