package io.github.returnaction.fitness.identity.registration.exception;

import lombok.Getter;

/**
 * Регистрация конфликтует с уже сохранёнными данными. Например, email уже занят
 * или тот же Idempotency-Key использован с другими данными. code уточняет
 * причину конфликта.
 */
@Getter
public class RegistrationConflictException extends RuntimeException {

    private final String code;

    public RegistrationConflictException(String code) {
        this.code = code;
    }
}
