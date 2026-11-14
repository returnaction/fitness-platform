package io.github.returnaction.fitness.exercisecatalog.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Преобразует бизнес-отказы контроллеров в согласованный ответ без перехода
 * на /error, который закрыт anyRequest().denyAll().
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> handleStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return response(status, exception.getReason() == null ? status.name() : exception.getReason());
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String error) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(status.value(), error));
    }

    record ApiErrorResponse(int status, String error) {
    }
}
