package io.github.returnaction.fitness.coins.shared.web;

/**
 * Сигнал некорректного HTTP-запроса вне валидации DTO.
 *
 * <p>Spec 017 T004.8/T004.9: используется, например, для query string
 * и отображается в {@code 400 invalid_request}.</p>
 */
public final class InvalidRequestException extends RuntimeException {
}
