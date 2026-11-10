package io.github.returnaction.fitness.gateway.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

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
                        "code", code, "message", "Request could not be completed.", "details", List.of(), "request_id",
                        requestId))));
    }
}
