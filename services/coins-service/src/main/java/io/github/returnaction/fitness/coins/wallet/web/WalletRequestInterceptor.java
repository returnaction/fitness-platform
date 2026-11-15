package io.github.returnaction.fitness.coins.wallet.web;

import io.github.returnaction.fitness.coins.shared.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Проверяет запросы Coins до контроллера.
 *
 * <p>Spec 017 T004.7/T004.9: для {@code GET /api/v1/coins/me} выставляет
 * {@code Cache-Control: no-store} и отклоняет query string как
 * {@code invalid_request}.</p>
 */
public final class WalletRequestInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/api/v1/coins/me".equals(path) && "GET".equals(request.getMethod())) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            if (request.getQueryString() != null) {
                throw new InvalidRequestException();
            }
        }
        return true;
    }
}
