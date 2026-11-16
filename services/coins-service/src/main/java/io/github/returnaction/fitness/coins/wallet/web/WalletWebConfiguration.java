package io.github.returnaction.fitness.coins.wallet.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Подключает проверки HTTP-запросов Coins.
 *
 * <p>Регистрирует {@link WalletRequestInterceptor} для всех HTTP-путей Coins:
 * баланс, статус и получение ежедневной награды.</p>
 */
@Configuration
public class WalletWebConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WalletRequestInterceptor()).addPathPatterns("/api/v1/coins/**");
    }
}
