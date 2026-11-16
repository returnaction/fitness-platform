package io.github.returnaction.fitness.coins.wallet.web;

import io.github.returnaction.fitness.coins.shared.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;


public final class WalletRequestInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v1/coins/")) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            if (request.getQueryString() != null) {
                throw new InvalidRequestException();
            }
        }
        return true;
    }
}
