package io.github.returnaction.fitness.identity.registration.exception;

/**
 * Внутренняя ошибка: регистрация ещё не завершена и сессию выдавать рано.
 * Например, профиль или кошелёк пользователя ещё готовится.
 */
public class RegistrationNotReadyException extends RuntimeException {
}
