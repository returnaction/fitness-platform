package io.github.returnaction.fitness.accounts.shared.security;

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

/**
 * Передаёт ошибки Spring Security в общий ApiExceptionHandler,
 * чтобы клиент получал единый JSON-формат ошибки.
 *
 * <p>Ошибка в фильтре возникает до контроллера, поэтому фильтр
 * или Spring Security явно вызывают этот обработчик:
 * <ul>
 *   <li>{@code commence()} — пользователь не аутентифицирован, ответ 401.</li>
 *   <li>{@code handle()} — пользователю запрещён доступ, ответ 403.</li>
 * </ul>
 *
 * <p>Устанавливает {@code Cache-Control: no-store} и передаёт исключение
 * через HandlerExceptionResolver в ApiExceptionHandler.
 * Тот формирует HTTP-статус и JSON ошибки.
 *
 * <p>Если ответ уже отправлен — ничего не меняет.
 * Если исключение не удалось обработать — возвращает статус 500.
 */
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

        resolve(request, response, exception);
    }

    @Override
    public void handle(@NonNull HttpServletRequest request,
                       @NonNull HttpServletResponse response,
                       @NonNull AccessDeniedException exception) {

        resolve(request, response, exception);
    }

    private void resolve(HttpServletRequest request, HttpServletResponse response, Exception exception) {
        if (response.isCommitted()) {
            return;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (exceptionResolver.resolveException(request, response, null, exception) == null && !response.isCommitted()) {
            response.resetBuffer();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
