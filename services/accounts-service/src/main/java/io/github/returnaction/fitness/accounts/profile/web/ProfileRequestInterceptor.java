package io.github.returnaction.fitness.accounts.profile.web;

import io.github.returnaction.fitness.accounts.shared.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Проверяет GET и PUT запросы к /api/v1/accounts/me перед вызовом контроллера.
 *
 * <ul>
 *   <li>Устанавливает Cache-Control: no-store, запрещая кеширование ответа.</li>
 *   <li>Отклоняет запросы с query-параметрами, например ?userId=123:
 *       выбрасывает InvalidRequestException, который обработчик ошибок
 *       преобразует в 400 invalid_request.</li>
 *   <li>Если query-параметров нет — пропускает запрос в контроллер.</li>
 * </ul>
 *
 * <p>Не проверяет токен и не читает SecurityContext:
 * аутентификация выполняется ранее в Spring Security.
 */
public final class ProfileRequestInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/api/v1/accounts/me".equals(path)
                && ("GET".equals(request.getMethod()) || "PUT".equals(request.getMethod()))) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            if (request.getQueryString() != null) {
                throw new InvalidRequestException();
            }
        }
        return true;
    }
}
