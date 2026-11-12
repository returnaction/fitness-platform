package io.github.returnaction.fitness.gateway.error;

import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class SecurityErrorHandler
        implements
            AuthenticationEntryPoint,
            AccessDeniedHandler {

    private final GatewayErrorWriter errorWriter;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        errorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                GatewayErrorCode.INVALID_ACCESS_TOKEN, requestId(request));
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        errorWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                GatewayErrorCode.ACCESS_DENIED, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return RequestIdResolver.getOrCreate(request);
    }
}
