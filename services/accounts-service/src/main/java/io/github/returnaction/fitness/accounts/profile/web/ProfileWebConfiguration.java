package io.github.returnaction.fitness.accounts.profile.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Подключает ProfileRequestInterceptor к запросам /api/v1/accounts/me.
 *
 * <p>Перед вызовом контроллера interceptor запрещает query-параметры
 * и устанавливает Cache-Control: no-store для GET и PUT.
 */
@Configuration
public class ProfileWebConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ProfileRequestInterceptor()).addPathPatterns("/api/v1/accounts/me");
    }
}
