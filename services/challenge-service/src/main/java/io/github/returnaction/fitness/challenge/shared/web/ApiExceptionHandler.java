package io.github.returnaction.fitness.challenge.shared.web;

import io.github.returnaction.fitness.challenge.domain.InvalidStateTransitionException;
import io.github.returnaction.fitness.challenge.shared.exception.BusinessConflictException;
import io.github.returnaction.fitness.challenge.shared.exception.DomainValidationException;
import io.github.returnaction.fitness.challenge.shared.exception.InvalidRequestException;
import io.github.returnaction.fitness.challenge.shared.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String NO_STORE = "no-store";
    private static final String VALIDATION_ERROR_CODE = "validation_error";
    private static final String VALIDATION_ERROR_MESSAGE = "Check the submitted data.";
    private static final String INVALID_VALUE_CODE = "invalid_value";

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
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
        return response(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_ERROR_CODE, VALIDATION_ERROR_MESSAGE,
                details, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiErrorResponse> handleMethodValidation(HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_ERROR_CODE, VALIDATION_ERROR_MESSAGE,
                List.of(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception,
                                                               HttpServletRequest request) {
        var details = exception.getConstraintViolations()
                .stream()
                .map(violation -> new ApiErrorResponse.ErrorDetail(
                        violation.getPropertyPath().toString(), INVALID_VALUE_CODE))
                .toList();
        return response(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_ERROR_CODE, VALIDATION_ERROR_MESSAGE,
                details, request);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidTransition(InvalidStateTransitionException exception,
                                                             HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_ERROR_CODE, VALIDATION_ERROR_MESSAGE,
                List.of(new ApiErrorResponse.ErrorDetail("status", "invalid_transition")), request);
    }

    @ExceptionHandler(DomainValidationException.class)
    ResponseEntity<ApiErrorResponse> handleDomainValidation(DomainValidationException exception,
                                                            HttpServletRequest request) {
        var details = exception.getDetails().stream()
                .map(detail -> new ApiErrorResponse.ErrorDetail(detail.field(), detail.code()))
                .toList();
        return response(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_ERROR_CODE, VALIDATION_ERROR_MESSAGE,
                details, request);
    }

    @ExceptionHandler({ResourceNotFoundException.class, NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiErrorResponse> handleNotFound(Exception exception, HttpServletRequest request) {
        if (exception instanceof ResourceNotFoundException notFound) {
            return response(HttpStatus.NOT_FOUND, notFound.getCode(), notFound.getMessage(), List.of(), request);
        }
        return response(HttpStatus.NOT_FOUND, "not_found", "Request could not be completed.", List.of(), request);
    }

    @ExceptionHandler(BusinessConflictException.class)
    ResponseEntity<ApiErrorResponse> handleBusinessConflict(BusinessConflictException exception,
                                                            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage(), List.of(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> handleAuthentication(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(errorBody("unauthenticated", "Authentication required.", List.of(), request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(errorBody("access_denied", "Access denied.", List.of(), request));
    }

    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class})
    ResponseEntity<ApiErrorResponse> handleUnavailable(Exception exception, HttpServletRequest request) {
        log.warn("Challenge storage unavailable, request_id={}, error_type={}",
                requestId(request), exception.getClass().getName());
        return response(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
                "Challenge is temporarily unavailable.", List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(exception.getHeaders())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(errorBody("unsupported_media_type", "Content type is not supported.", List.of(), request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(exception.getHeaders())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(errorBody("method_not_allowed", "HTTP method is not supported.", List.of(), request));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> handleNotAcceptable() {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure, request_id={}, error_type={}",
                requestId(request), exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error.",
                List.of(), request);
    }

    private String validationCode(String constraint) {
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty", "required" -> "required";
            case "Size", "invalid_length" -> "invalid_length";
            case "Pattern", "Email", "invalid_format" -> "invalid_format";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Negative",
                 "NegativeOrZero", "out_of_range" -> "out_of_range";
            case null, default -> INVALID_VALUE_CODE;
        };
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

    private ApiErrorResponse errorBody(String code,
                                       String message,
                                       List<ApiErrorResponse.ErrorDetail> details,
                                       HttpServletRequest request) {
        return new ApiErrorResponse(new ApiErrorResponse.Error(code, message, details, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        String attribute = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return attribute != null ? attribute
                : RequestIdResolver.parseOrGenerate(request.getHeader("X-Request-Id")).toString();
    }
}
