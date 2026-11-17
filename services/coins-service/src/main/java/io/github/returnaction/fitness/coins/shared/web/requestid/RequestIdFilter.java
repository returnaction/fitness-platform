package io.github.returnaction.fitness.coins.shared.web.requestid;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Назначает ID запроса. Регистрация и порядок заданы в ServletFiltersConfiguration. */
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
