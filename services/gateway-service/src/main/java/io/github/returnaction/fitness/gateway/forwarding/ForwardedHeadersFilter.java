package io.github.returnaction.fitness.gateway.forwarding;

import io.github.returnaction.fitness.gateway.request.RequestIdFilter;
import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public final class ForwardedHeadersFilter implements RequestHttpHeadersFilter {

    private final RouteAccessPolicy accessPolicy;

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final List<String> ALLOWED_HEADERS = List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT, "Idempotency-Key");

    @Override
    public HttpHeaders apply(HttpHeaders headers, ServerRequest request) {
        HttpHeaders forwarded = new HttpHeaders();
        for (String name : ALLOWED_HEADERS) {
            List<String> values = headers.get(name);
            if (values != null) {
                forwarded.put(name, List.copyOf(values));
            }
        }
        if (accessPolicy.publicRequests().matches(request.servletRequest())) {
            forwarded.remove(HttpHeaders.AUTHORIZATION);
        }
        forwarded.set(REQUEST_ID_HEADER, requireRequestId(request));
        forwarded.set("X-Forwarded-For", request.servletRequest().getRemoteAddr());
        return forwarded;
    }

    private static String requireRequestId(ServerRequest request) {
        Object value = request.servletRequest().getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (!(value instanceof String requestId) || requestId.isBlank()) {
            throw new IllegalStateException("RequestIdFilter must provide request_id before proxying");
        }
        return requestId;
    }
}
