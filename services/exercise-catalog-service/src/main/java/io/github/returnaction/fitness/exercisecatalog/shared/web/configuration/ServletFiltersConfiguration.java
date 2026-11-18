package io.github.returnaction.fitness.exercisecatalog.shared.web.configuration;

import io.github.returnaction.fitness.exercisecatalog.shared.web.requestid.RequestIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Назначает requestId до Security, включая ответы с ошибками аутентификации. */
@Configuration
public class ServletFiltersConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setName("requestIdFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
