package io.github.returnaction.fitness.gateway.error;

import io.github.returnaction.fitness.gateway.error.dto.ApiErrorResponse;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import io.github.returnaction.fitness.gateway.timeout.HttpDeadlineScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CancellationException;

@Slf4j
@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler({ResourceAccessException.class, IOException.class, UncheckedIOException.class,
            CancellationException.class})
    ResponseEntity<ApiErrorResponse> transportFailure(Exception failure, HttpServletRequest request,
        HttpServletResponse response) throws Exception {
        var scope = (HttpDeadlineScope) request.getAttribute(HttpDeadlineScope.ATTRIBUTE);
        boolean timeout = (scope != null && scope.timedOut()) || causedBy(failure, SocketTimeoutException.class);
        if (scope != null) {
            scope.close();
        }
        if (response.isCommitted() || (!timeout && causedBy(failure, ClientAbortException.class))) {
            throw failure;
        }
        response.reset();
        return error(timeout ? 504 : 502,
                timeout ? GatewayErrorCode.UPSTREAM_TIMEOUT : GatewayErrorCode.UPSTREAM_UNAVAILABLE, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException failure,
        HttpServletRequest request) {
        return ResponseEntity.status(failure.getStatusCode())
                .headers(failure.getHeaders())
                .header("X-Request-Id", RequestIdResolver.getOrCreate(request))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body(GatewayErrorCode.METHOD_NOT_ALLOWED, request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException failure,
        HttpServletRequest request) {
        return ResponseEntity.status(failure.getStatusCode())
                .headers(failure.getHeaders())
                .header("X-Request-Id", RequestIdResolver.getOrCreate(request))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body(GatewayErrorCode.UNSUPPORTED_MEDIA_TYPE, request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception failure, HttpServletRequest request,
        HttpServletResponse response) throws Exception {
        if (response.isCommitted()) {
            throw failure;
        }
        log.error("Unhandled request failure, request_id={}, error_type={}", RequestIdResolver.getOrCreate(request),
                failure.getClass().getName());
        return error(500, GatewayErrorCode.INTERNAL_ERROR, request);
    }

    private ResponseEntity<ApiErrorResponse> error(int status, GatewayErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .header("X-Request-Id", RequestIdResolver.getOrCreate(request))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body(code, request));
    }

    private ApiErrorResponse body(GatewayErrorCode code, HttpServletRequest request) {
        return new ApiErrorResponse(new ApiErrorResponse.Error(code.getCode(), code.getMessage(), List.of(),
                RequestIdResolver.getOrCreate(request)));
    }

    private boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
