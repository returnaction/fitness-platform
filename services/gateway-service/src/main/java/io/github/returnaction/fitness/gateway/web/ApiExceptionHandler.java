package io.github.returnaction.fitness.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
public final class ApiExceptionHandler {
    private final ApiErrors errors;
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Stateless error writer is an injected shared component")
    public ApiExceptionHandler(ApiErrors errors) {
        this.errors = errors;
    }

    @ExceptionHandler(Exception.class)
    void handle(Exception exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        int status = exception instanceof ResourceAccessException ? 502 : 500;
        String code = status == 502 ? "upstream_unavailable" : "internal_error";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                status = 504;
                code = "upstream_timeout";
                break;
            }
        }
        errors.write(response, status, code, String.valueOf(request.getAttribute("request_id")));
    }
}
