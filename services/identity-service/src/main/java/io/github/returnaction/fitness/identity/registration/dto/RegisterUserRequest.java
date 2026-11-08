package io.github.returnaction.fitness.identity.registration.dto;

/**
 * Внешнее тело запроса регистрации. Пароль не имеет {@code toString()} в
 * record-логах.
 */
public record RegisterUserRequest(String email, String password) {

    @Override
    public String toString() {
        return "RegisterUserRequest[email=<redacted>, password=<redacted>]";
    }
}
