package io.github.returnaction.fitness.identity.shared.web;

import io.github.returnaction.fitness.identity.registration.exception.InvalidIdempotencyKeyException;
import io.github.returnaction.fitness.identity.shared.exception.InvalidAccessTokenException;
import io.github.returnaction.fitness.identity.registration.exception.RecoveryConflictException;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationConflictException;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationUnavailableException;
import io.github.returnaction.fitness.identity.shared.exception.CredentialsValidationException;
import io.github.returnaction.fitness.identity.session.exception.AuthenticationFailureException;
import io.github.returnaction.fitness.identity.session.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            InvalidIdempotencyKeyException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiErrorResponse> handleInvalidRequest(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Check the request format.", List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception,
        HttpServletRequest request) {
        var builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        var supportedMethods = exception.getSupportedHttpMethods();
        if (supportedMethods != null && !supportedMethods.isEmpty()) {
            builder.allow(supportedMethods.toArray(org.springframework.http.HttpMethod[]::new));
        }
        return builder.body(errorBody("method_not_allowed", "HTTP method is not supported.", List.of(), request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(exception.getHeaders())
                .body(errorBody("unsupported_media_type", "Content type is not supported.", List.of(), request));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> handleNotAcceptable() {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(AuthenticationFailureException.class)
    ResponseEntity<ApiErrorResponse> handleAuthenticationFailure(AuthenticationFailureException exception,
        HttpServletRequest request) {
        HttpStatus status = switch (exception.reason()) {
            case INVALID_CREDENTIALS, INVALID_REFRESH -> HttpStatus.UNAUTHORIZED;
            case ACCOUNT_UNAVAILABLE, REFRESH_ACCOUNT_UNAVAILABLE -> HttpStatus.FORBIDDEN;
            case REGISTRATION_PENDING -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String code = switch (exception.reason()) {
            case INVALID_CREDENTIALS -> "invalid_credentials";
            case ACCOUNT_UNAVAILABLE, REFRESH_ACCOUNT_UNAVAILABLE -> "account_unavailable";
            case REGISTRATION_PENDING -> "registration_pending";
            case INVALID_REFRESH -> "invalid_refresh_token";
        };
        String message = switch (exception.reason()) {
            case INVALID_CREDENTIALS -> "Invalid email or password.";
            case ACCOUNT_UNAVAILABLE -> "Account is unavailable.";
            case REGISTRATION_PENDING -> "Registration is being prepared. Try again later.";
            case INVALID_REFRESH, REFRESH_ACCOUNT_UNAVAILABLE -> "Request could not be completed.";
        };
        return response(status, code, message, List.of(), request, HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @ExceptionHandler(InvalidAccessTokenException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidAccess(InvalidAccessTokenException exception,
        HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "invalid_access_token", "Authentication required.", List.of(),
                request);
    }

    @ExceptionHandler(RecoveryConflictException.class)
    ResponseEntity<ApiErrorResponse> handleRecoveryConflict(RecoveryConflictException exception,
        HttpServletRequest request) {
        HttpStatus status = "not_found".equals(exception.code()) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return response(status, exception.code(), "Registration recovery could not be completed.", List.of(), request);
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    ResponseEntity<ApiErrorResponse> handleDatabaseFailure(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "identity_unavailable", "Identity service is unavailable.",
                List.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception,
        HttpServletRequest request) {
        List<ApiErrorResponse.ErrorDetail> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(field -> exception.getBindingResult().getTarget() instanceof LoginRequest
                        ? new ApiErrorResponse.ErrorDetail(field.getField(), field.getDefaultMessage())
                        : toErrorDetail(field))
                .sorted(java.util.Comparator
                        .comparingInt(field -> exception.getBindingResult().getTarget() instanceof LoginRequest
                                && "email".equals(field.field()) ? 0 : 1))
                .toList();

        return response(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Check the submitted data.", details,
                request);
    }

    @ExceptionHandler(RegistrationUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleRegistrationUnavailable(HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "registration_unavailable", "Registration is being prepared.",
                List.of(), request);
    }

    @ExceptionHandler(CredentialsValidationException.class)
    ResponseEntity<ApiErrorResponse> handleCredentialsValidation(CredentialsValidationException exception,
        HttpServletRequest request) {
        List<ApiErrorResponse.ErrorDetail> details = exception.getViolations()
                .stream()
                .map(violation -> new ApiErrorResponse.ErrorDetail(violation.field(), violation.code()))
                .toList();
        return response(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Check the submitted data.", details,
                request);
    }

    @ExceptionHandler(RegistrationConflictException.class)
    ResponseEntity<ApiErrorResponse> handleRegistrationConflict(RegistrationConflictException exception,
        HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getCode(), "Registration conflicts with existing data.",
                List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {

        String requestId = requestId(request);
        log.error("Unhandled request failure, request_id={}, error_type={}", requestId, exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error.", List.of(),
                request);
    }

    private ApiErrorResponse.ErrorDetail toErrorDetail(FieldError fieldError) {
        return new ApiErrorResponse.ErrorDetail(fieldError.getField(), "invalid");
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message,
        List<ApiErrorResponse.ErrorDetail> details, HttpServletRequest request) {

        return ResponseEntity.status(status).body(errorBody(code, message, details, request));
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message,
        List<ApiErrorResponse.ErrorDetail> details, HttpServletRequest request, String headerName, String headerValue) {

        return ResponseEntity.status(status)
                .header(headerName, headerValue)
                .body(errorBody(code, message, details, request));
    }

    private ApiErrorResponse errorBody(String code, String message, List<ApiErrorResponse.ErrorDetail> details,
        HttpServletRequest request) {

        return new ApiErrorResponse(new ApiErrorResponse.Error(code, message, details, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId instanceof String value ? value : RequestIdResolver.parseOrGenerate(null).toString();
    }
}
