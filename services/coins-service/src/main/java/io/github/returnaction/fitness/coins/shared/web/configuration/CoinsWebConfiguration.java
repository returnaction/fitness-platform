package io.github.returnaction.fitness.coins.shared.web.configuration;

import io.github.returnaction.fitness.coins.shared.web.interceptor.NoStoreResponseInterceptor;
import io.github.returnaction.fitness.coins.shared.web.interceptor.NoQueryParametersInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Подключает правила кеширования и query-параметров только к явно указанным endpoints. */
@Configuration
public class CoinsWebConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Настройка при запуске, не шаг каждого запроса: регистрируем MVC-interceptors в порядке 3-A → 3-B.
        // Они выполняются после разрешения доступа в Spring Security (2-A), перед контроллером.
        // Эти ответы содержат текущие балансы и состояние подарка: сохранять их в кеше нельзя.
        // Заголовок ставим до проверки параметров, чтобы он был и в ответе с ошибкой.
        // Шаг 3-A выполняется в NoStoreResponseInterceptor.preHandle().
        registry.addInterceptor(new NoStoreResponseInterceptor())
            .order(0)
            .addPathPatterns(
                "/api/v1/coins/me",
                "/api/v1/coins/daily-reward/status",
                "/api/v1/coins/daily-reward/claims"
            );

        // По контракту эти endpoints не принимают параметры после "?".
        // Список отдельный: будущему endpoint могут понадобиться параметры, но не кеширование.
        // Шаг 3-B выполняется в NoQueryParametersInterceptor.preHandle().
        registry.addInterceptor(new NoQueryParametersInterceptor())
            .order(1)
            .addPathPatterns(
                "/api/v1/coins/me",
                "/api/v1/coins/daily-reward/status",
                "/api/v1/coins/daily-reward/claims"
            );
    }
}
