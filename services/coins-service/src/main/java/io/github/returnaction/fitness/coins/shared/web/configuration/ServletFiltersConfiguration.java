package io.github.returnaction.fitness.coins.shared.web.configuration;

import io.github.returnaction.fitness.coins.shared.web.requestid.RequestIdFilter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Регистрирует наши servlet-фильтры и задаёт порядок их выполнения.
 * Фильтры внутри Spring Security настраиваются отдельно в SecurityConfiguration,
 * а MVC-interceptors — в CoinsWebConfiguration.
 */
@Configuration
public class ServletFiltersConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setName("requestIdFilter");
        registration.addUrlPatterns("/*");

        // Шаг 0: назначаем ID раньше Spring Security, чтобы он был даже в ответе с ошибкой авторизации.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
