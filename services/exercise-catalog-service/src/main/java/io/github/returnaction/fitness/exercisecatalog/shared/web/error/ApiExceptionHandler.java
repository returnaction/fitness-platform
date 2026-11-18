package io.github.returnaction.fitness.exercisecatalog.shared.web.error;

import io.github.returnaction.fitness.exercisecatalog.shared.web.requestid.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.Arrays;
import java.util.List;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    private static final String NO_STORE = "no-store";

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> handleAuthentication(HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .body(errorBody("invalid_access_token", "Authentication required.", List.of(), request));

    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .body(errorBody("access_denied", "Access denied.", List.of(), request));
    }

    @ExceptionHandler(CannotCreateTransactionException.class)
    ResponseEntity<ApiErrorResponse> handleCannotCreateTransaction(HttpServletRequest request) {

        log.warn("Database transaction could not be created, request_id={}", requestId(request));

        return unavailableResponse(request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidRequest(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Check the request format.", List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> handleNotAcceptable() {
        // Не пытаемся отправить JSON клиенту, который не принимает этот формат.
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .build();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
        HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .headers(exception.getHeaders())
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .body(errorBody("unsupported_media_type", "Content type is not supported.", List.of(), request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
        HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .headers(exception.getHeaders())
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .body(errorBody("method_not_allowed", "HTTP method is not supported.", List.of(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleException(Exception exception, HttpServletRequest request) {

        Throwable cause = exception.getCause();
        log.error("Unhandled request failure, request_id={}, error_type={}, cause_type={}, stack_trace={}",
            requestId(request), exception.getClass().getName(),
            cause == null ? "none" : cause.getClass().getName(),
            Arrays.toString(exception.getStackTrace()));

        return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error.", List.of(), request);
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status,
                                                      String code,
                                                      String message,
                                                      List<ApiErrorResponse.ErrorDetail> details,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(status)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .body(errorBody(code, message, details, request));
    }

    private ResponseEntity<ApiErrorResponse> unavailableResponse(HttpServletRequest request) {
        // Предлагаем повторить GET через секунду.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(errorBody(
                "catalog_unavailable",
                "Exercise Catalog service is temporarily unavailable.",
                List.of(),
                request
            ));
    }

    private ApiErrorResponse errorBody(String code,
                                       String message,
                                       List<ApiErrorResponse.ErrorDetail> details,
                                       HttpServletRequest request) {

        return new ApiErrorResponse(new ApiErrorResponse.Error(code, message, details, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
