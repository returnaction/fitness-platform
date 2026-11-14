package io.github.returnaction.fitness.moderation.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Добавляет идентификатор к каждому входящему HTTP-запросу.
 *
 * Простой пример:
 *
 * 1. Клиент отправляет запрос на регистрацию. 2. Фильтр получает или создаёт
 * requestId, например: 550e8400-e29b-41d4-a716-446655440000 3. Этот же
 * идентификатор возвращается клиенту в заголовке X-Request-Id. 4. Идентификатор
 * сохраняется в запросе и в MDC — контексте логирования. 5. Если при обработке
 * возникнет ошибка, её можно связать с этим запросом.
 *
 * Зачем это нужно: пользователь говорит: «У меня ошибка», и присылает
 * X-Request-Id. По этому идентификатору разработчик может искать нужный запрос
 * в логах.
 *
 * Идентификатор не является паролем или токеном и не даёт доступ к аккаунту.
 * После обработки запроса идентификатор удаляется из MDC потока.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "request_id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        String requestId = RequestIdResolver.parseOrGenerate(request.getHeader(REQUEST_ID_HEADER)).toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try (MDC.MDCCloseable ignored = MDC.putCloseable(REQUEST_ID_ATTRIBUTE, requestId)) {
            filterChain.doFilter(request, response);
        }
    }
}
