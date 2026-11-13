package io.github.returnaction.fitness.accounts.shared.web;

import io.github.returnaction.fitness.accounts.profile.exception.NicknameAlreadyTakenException;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileUnavailableException;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileValidationException;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileVersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        InvalidRequestException.class})
    ResponseEntity<ApiErrorResponse> handleInvalidRequest(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request", "Check the request format.", List.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception,
                                                      HttpServletRequest request) {
        var details = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(field -> new ApiErrorResponse.ErrorDetail(field.getField(), validationCode(field.getCode())))
            .toList();
        return response(HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", "Check the submitted data.", details,
            request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .headers(exception.getHeaders())
            .body(errorBody("unsupported_media_type", "Content type is not supported.", List.of(), request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .headers(exception.getHeaders())
            .body(errorBody("method_not_allowed", "HTTP method is not supported.", List.of(), request));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> handleNotAcceptable() {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> handleAuthentication(HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(errorBody("invalid_access_token", "Authentication required.", List.of(), request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(errorBody("access_denied", "Access denied.", List.of(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {

        log.error("Unhandled request failure, request_id={}, error_type={}",
            requestId(request), exception.getClass().getName());

        return response(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal_error",
            "Unexpected server error.",
            List.of(),
            request);
    }

    @ExceptionHandler(ProfileValidationException.class)
    ResponseEntity<ApiErrorResponse> handleProfileValidation(ProfileValidationException exception,
                                                             HttpServletRequest request) {

        return response(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "validation_error",
            "Check the submitted data.",
            exception.getDetails(), request
        );
    }

    @ExceptionHandler(ProfileVersionConflictException.class)
    ResponseEntity<ApiErrorResponse> handleProfileVersionConflict(HttpServletRequest request) {

        return response(
            HttpStatus.CONFLICT,
            "profile_version_conflict",
            "Profile has changed. Reload it before saving.",
            List.of(),
            request
        );
    }

    @ExceptionHandler(NicknameAlreadyTakenException.class)
    ResponseEntity<ApiErrorResponse> handleNicknameAlreadyTaken(HttpServletRequest request) {

        return response(
            HttpStatus.CONFLICT,
            "nickname_already_taken",
            "Nickname is already taken.",
            List.of(),
            request
        );
    }

    @ExceptionHandler(ProfileUnavailableException.class)
    ResponseEntity<ApiErrorResponse> handleProfileUnavailable(ProfileUnavailableException exception,
                                                              HttpServletRequest request) {

        Throwable cause = exception.getCause();
        String reason = cause == null ? "profile_missing" : cause.getClass().getSimpleName();

        log.warn("Profile unavailable, request_id={}, reason={}", requestId(request), reason);

        return response(
            HttpStatus.SERVICE_UNAVAILABLE,
            "profile_unavailable",
            "Profile is temporarily unavailable.",
            List.of(), request
        );
    }

    private String validationCode(String constraint) {
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty", "required" -> "required";
            case "Size", "invalid_length" -> "invalid_length";
            case "Pattern", "Email", "invalid_format" -> "invalid_format";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
                 "out_of_range" -> "out_of_range";
            case null, default -> "invalid_value";
        };
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status,
                                                      String code,
                                                      String message,
                                                      List<ApiErrorResponse.ErrorDetail> details,
                                                      HttpServletRequest request) {

        return ResponseEntity.status(status).body(errorBody(code, message, details, request));
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
