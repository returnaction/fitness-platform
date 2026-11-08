package io.github.returnaction.fitness.identity.registration.exception;

/**
 * Клиент отправил слишком много запросов за короткое время. Например, слишком
 * часто пытался зарегистрироваться. retryAfterSeconds показывает, через сколько
 * секунд можно повторить запрос.
 */
public class RegistrationRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public RegistrationRateLimitException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
