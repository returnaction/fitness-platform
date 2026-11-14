package io.github.returnaction.fitness.accounts.profile.web;

import io.github.returnaction.fitness.accounts.shared.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

public final class ProfileRequestInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
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
