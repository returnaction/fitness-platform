package io.github.returnaction.fitness.identity.registration.exception;

import lombok.Getter;

import java.util.List;

/**
 * Данные регистрации не прошли проверку. Например, email записан неверно или
 * пароль отсутствует. violations содержит имя каждого проблемного поля и код
 * ошибки.
 */
@Getter
public class RegistrationValidationException extends RuntimeException {

    private final List<Violation> violations;

    public RegistrationValidationException(List<Violation> violations) {
        this.violations = List.copyOf(violations);
    }

    public record Violation(String field, String code) {
    }
}
