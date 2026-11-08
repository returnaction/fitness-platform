package io.github.returnaction.fitness.identity.registration.exception;

/**
 * Регистрация пока недоступна для завершения и выдачи токенов. Например,
 * профиль или кошелёк ещё не подготовлен. Клиент может повторить запрос с тем
 * же Idempotency-Key.
 */
public class RegistrationUnavailableException extends RuntimeException {
}
