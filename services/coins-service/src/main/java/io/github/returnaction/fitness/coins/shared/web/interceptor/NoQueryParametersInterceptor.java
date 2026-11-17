package io.github.returnaction.fitness.coins.shared.web.interceptor;

import io.github.returnaction.fitness.coins.shared.web.error.InvalidRequestException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/** Отклоняет запрос с параметрами после "?". Пути задаются в CoinsWebConfiguration. */
public final class NoQueryParametersInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        // Шаг 3-B: проверяем отсутствие параметров после "?" у подключённых endpoints.
        if (request.getQueryString() != null) {
            // Ошибка MVC: ApiExceptionHandler вернёт 400. SecurityExceptionHandler здесь не участвует.
            // Контроллер не вызывается; при выходе из цепочки фильтр очистит контекст (4-A).
            throw new InvalidRequestException();
        }
        // Шаг 3-C: проверки пройдены. Spring MVC продолжит подготовку аргументов и вызовет контроллер.
        // Если подготовка аргументов завершится ошибкой, её обработает ApiExceptionHandler.
        return true;
    }
}
