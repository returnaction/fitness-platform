package io.github.returnaction.fitness.coins.wallet.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Подключает проверки HTTP-запросов Coins.
 *
 * <p>Spec 017 T004.7/T004.9: регистрирует {@link WalletRequestInterceptor}
 * для пути {@code /api/v1/coins/me}.</p>
 */
@Configuration
public class WalletWebConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WalletRequestInterceptor()).addPathPatterns("/api/v1/coins/me");
    }
}
