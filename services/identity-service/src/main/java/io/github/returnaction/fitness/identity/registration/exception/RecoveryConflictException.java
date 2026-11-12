package io.github.returnaction.fitness.identity.registration.exception;

/**
 * Операцию восстановления регистрации невозможно выполнить в текущем состоянии.
 * Например, операция не найдена или её версия уже изменилась. code уточняет
 * причину отказа.
 */
public class RecoveryConflictException extends RuntimeException {

    private final String code;

    public RecoveryConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
