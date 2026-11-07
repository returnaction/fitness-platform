package io.github.returnaction.fitness.identity.shared.web;

import io.github.returnaction.fitness.identity.registration.application.InvalidIdempotencyKeyException;
import io.github.returnaction.fitness.identity.registration.application.RegistrationConflictException;
import io.github.returnaction.fitness.identity.registration.application.RegistrationValidationException;
import io.github.returnaction.fitness.identity.registration.web.RegistrationRateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RegistrationRateLimitException.class)
    ResponseEntity<ApiErrorResponse> handleRateLimit(
            RegistrationRateLimitException exception, HttpServletRequest request) {
        var body = response(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Try again later.", List.of(), request)
                .getBody();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Check the request format.", List.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiErrorResponse.ErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toErrorDetail)
                .toList();
        return response(
                HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Check the submitted data.", details, request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidIdempotencyKey(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Check the request format.", List.of(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> handleMissingRequestHeader(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Check the request format.", List.of(), request);
    }

    @ExceptionHandler(RegistrationValidationException.class)
    ResponseEntity<ApiErrorResponse> handleRegistrationValidation(
            RegistrationValidationException exception, HttpServletRequest request) {
        List<ApiErrorResponse.ErrorDetail> details = exception.getViolations().stream()
                .map(violation -> new ApiErrorResponse.ErrorDetail(violation.field(), violation.code()))
                .toList();
        return response(
                HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Check the submitted data.", details, request);
    }

    @ExceptionHandler(RegistrationConflictException.class)
    ResponseEntity<ApiErrorResponse> handleRegistrationConflict(
            RegistrationConflictException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                exception.getCode(),
                "Registration conflicts with existing data.",
                List.of(),
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        LOGGER.error("Unhandled request failure, request_id={}", requestId, exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error.", List.of(), request);
    }

    private ApiErrorResponse.ErrorDetail toErrorDetail(FieldError fieldError) {
        return new ApiErrorResponse.ErrorDetail(fieldError.getField(), "invalid");
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            List<ApiErrorResponse.ErrorDetail> details,
            HttpServletRequest request) {
        ApiErrorResponse body =
                new ApiErrorResponse(new ApiErrorResponse.Error(code, message, details, requestId(request)));
        return ResponseEntity.status(status).body(body);
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId instanceof String value
                ? value
                : RequestIdResolver.resolve(null).toString();
    }
}
