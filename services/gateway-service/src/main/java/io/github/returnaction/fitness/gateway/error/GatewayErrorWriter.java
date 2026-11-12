package io.github.returnaction.fitness.gateway.error;

import io.github.returnaction.fitness.gateway.error.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public final class GatewayErrorWriter {

    private final ObjectMapper mapper;

    public void write(HttpServletResponse response,
                      int status,
                      GatewayErrorCode error,
                      String requestId) throws IOException {
        if (response.isCommitted()) {
            throw new IllegalStateException(
                    "Cannot write an API error after response is committed");
        }
        var body = new ApiErrorResponse(
                new ApiErrorResponse.Error(error.getCode(), error.getMessage(), List.of(), requestId));
        byte[] json = mapper.writeValueAsBytes(body);

        response.resetBuffer();
        response.setHeader(HttpHeaders.CONTENT_ENCODING, null);
        response.setHeader(HttpHeaders.TRANSFER_ENCODING, null);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(json.length);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Request-Id", requestId);
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.getOutputStream().write(json);
    }

}
