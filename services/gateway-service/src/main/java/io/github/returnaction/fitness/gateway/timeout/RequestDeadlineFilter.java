package io.github.returnaction.fitness.gateway.timeout;

import io.github.returnaction.fitness.gateway.configuration.properties.RequestTimeoutProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestDeadlineFilter extends OncePerRequestFilter {

    private static final String DEADLINE_ATTRIBUTE = RequestDeadline.class.getName();
    private final RequestTimeoutProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getAttribute(DEADLINE_ATTRIBUTE) == null) {
            request.setAttribute(DEADLINE_ATTRIBUTE, RequestDeadline.start(properties.timeout()));
        }
        chain.doFilter(request, response);
    }

    public static RequestDeadline requireDeadline(HttpServletRequest request) {
        Object value = request.getAttribute(DEADLINE_ATTRIBUTE);
        if (value instanceof RequestDeadline deadline) {
            return deadline;
        }
        throw new IllegalStateException("Request deadline must be initialized before security");
    }
}
