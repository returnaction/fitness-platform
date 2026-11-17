package io.github.returnaction.fitness.coins.shared.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/** Запрещает сохранять ответ в кеше. Пути задаются в CoinsWebConfiguration. */
public final class NoStoreResponseInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        // Шаг 3-A: доступ уже разрешён. Запрещаем сохранять ответ в кеше.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        // Продолжаем к NoQueryParametersInterceptor.preHandle() — шагу 3-B.
        return true;
    }
}
