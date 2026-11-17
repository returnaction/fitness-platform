package io.github.returnaction.fitness.coins.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;


/** Ветка E: формирует ответ при отказе security. Это обработчик ошибок, а не очередной фильтр. */
@Component
public class SecurityExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver exceptionResolver;

    public SecurityExceptionHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void commence(@NonNull HttpServletRequest request,
                         @NonNull HttpServletResponse response,
                         @NonNull AuthenticationException exception) {

        // E-A: пользователя не удалось аутентифицировать. ApiExceptionHandler вернёт 401.
        resolve(request, response, exception);
    }

    @Override
    public void handle(@NonNull HttpServletRequest request,
                       @NonNull HttpServletResponse response,
                       @NonNull AccessDeniedException exception) {
        // E-B: пользователь аутентифицирован, но операция запрещена. ApiExceptionHandler вернёт 403.
        resolve(request, response, exception);
    }

    private void resolve(HttpServletRequest request, HttpServletResponse response, Exception exception) {

        // E-C: уже отправленный ответ переписать нельзя.
        if (response.isCommitted()) {
            return;
        }
        // E-D: до MVC-interceptors запрос мог не дойти, поэтому запрещаем кеширование ошибки здесь.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        // E-E: передаём исключение через Spring resolver в ApiExceptionHandler для единого JSON ошибки.
        // Если обработчик не найден и ответ ещё не отправлен — возвращаем запасной ответ 500.
        if (exceptionResolver.resolveException(request, response, null, exception) == null && !response.isCommitted()) {
            response.resetBuffer();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
