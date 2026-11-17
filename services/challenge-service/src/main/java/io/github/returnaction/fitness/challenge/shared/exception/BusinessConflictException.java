package io.github.returnaction.fitness.challenge.shared.exception;

import lombok.Getter;

/**
 * Бизнес-конфликт состояния или версии; HTTP-ответ 409 с кодом из спецификации.
 */
@Getter
public final class BusinessConflictException extends RuntimeException {

    private final String code;

    public BusinessConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

}
