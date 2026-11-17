package io.github.returnaction.fitness.challenge.shared.exception;

import lombok.Getter;

/**
 * Ресурс не найден или скрыт от текущего пользователя; HTTP-ответ 404.
 */
@Getter
public final class ResourceNotFoundException extends RuntimeException {

    private final String code;

    public ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

}
