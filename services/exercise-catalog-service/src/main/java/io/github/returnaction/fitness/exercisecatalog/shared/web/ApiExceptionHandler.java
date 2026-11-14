package io.github.returnaction.fitness.exercisecatalog.shared.web;

import io.github.returnaction.fitness.exercisecatalog.catalog.exception.InvalidCursorException;
import io.github.returnaction.fitness.exercisecatalog.catalog.exception.InvalidPaginationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidCursor(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Cursor is invalid.", request);
    }

    @ExceptionHandler(InvalidPaginationException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidPagination(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Page size must be between 1 and 100.",
            request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleTypeMismatch(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Check the request parameters.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure, request_id={}, error_type={}", requestId(request),
            exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error.", request);
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message,
        HttpServletRequest request) {

        return ResponseEntity.status(status).body(new ApiErrorResponse(
            new ApiErrorResponse.Error(code, message, List.of(), requestId(request))));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
