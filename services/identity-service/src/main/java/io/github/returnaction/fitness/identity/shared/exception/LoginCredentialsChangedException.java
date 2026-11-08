package io.github.returnaction.fitness.identity.shared.exception;

/**
 * Данные пользователя изменились во время входа после первой проверки.
 * Например, пароль сменили или аккаунт отключили до создания сессии. Вход
 * необходимо проверить повторно; старую проверку использовать нельзя.
 */
public class LoginCredentialsChangedException extends RuntimeException {
}
