package io.github.returnaction.fitness.challenge.shared.exception;

/**
 * Некорректный формат запроса; HTTP-ответ 400 invalid_request.
 */
public final class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
