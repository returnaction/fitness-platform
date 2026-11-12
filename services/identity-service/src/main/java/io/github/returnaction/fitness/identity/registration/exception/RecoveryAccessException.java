package io.github.returnaction.fitness.identity.registration.exception;

/**
 * Ошибка доступа к административному восстановлению регистрации. Например,
 * токен недействителен или у пользователя нет нужных прав. code уточняет
 * причину отказа.
 */
public class RecoveryAccessException extends RuntimeException {

    private final String code;

    public RecoveryAccessException(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
