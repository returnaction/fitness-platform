package io.github.returnaction.fitness.gateway.forwarding;

import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps MVC parameter access from consuming a form body. Query strings are
 * rejected by security.
 */
@Component
@Order(0)
@RequiredArgsConstructor
public final class ProxyRequestBodyFilter extends OncePerRequestFilter {

    private final RouteAccessPolicy policy;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !policy.publicRequests().matches(request) && !policy.protectedRequests().matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {

            @Override
            public Map<String, String[]> getParameterMap() {
                return Map.of();
            }

            @Override
            public Enumeration<String> getParameterNames() {
                return Collections.emptyEnumeration();
            }

            @Override
            public String getParameter(String name) {
                return null;
            }

            @Override
            public String[] getParameterValues(String name) {
                return null;
            }
        }, response);
    }
}
