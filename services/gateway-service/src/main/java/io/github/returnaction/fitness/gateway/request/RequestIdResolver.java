package io.github.returnaction.fitness.gateway.request;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class RequestIdResolver {

    private RequestIdResolver() {
    }

    public static String getOrCreate(HttpServletRequest request) {
        Object existing = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);
        return requestId;
    }
}
