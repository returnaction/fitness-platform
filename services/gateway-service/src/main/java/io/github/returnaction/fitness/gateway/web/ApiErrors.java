package io.github.returnaction.fitness.gateway.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public final class ApiErrors {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public void write(HttpServletResponse response, int status, String code, String requestId) throws IOException {
        if (response.isCommitted())
            return;
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Request-Id", requestId);
        if (status == 401)
            response.setHeader("WWW-Authenticate", "Bearer");
        response.getOutputStream()
                .write(mapper.writeValueAsBytes(Map.of("error", Map.of(
                        "code", code, "message", message(code), "details", List.of(), "request_id",
                        requestId))));
    }

    private static String message(String code) {
        return switch (code) {
            case "invalid_request" -> "Check the request format.";
            case "invalid_access_token" -> "Authentication required.";
            case "method_not_allowed" -> "HTTP method is not supported.";
            case "rate_limited" -> "Try again later.";
            case "identity_unavailable" -> "Identity service is unavailable.";
            case "internal_error" -> "Unexpected server error.";
            default -> "Request could not be completed.";
        };
    }
}
