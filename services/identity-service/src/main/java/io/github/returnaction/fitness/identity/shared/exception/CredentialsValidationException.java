package io.github.returnaction.fitness.identity.shared.exception;

import lombok.Getter;

import java.util.List;

/**
 * Учётные данные не прошли проверку. Например, email записан неверно или пароль
 * отсутствует. violations содержит имя каждого проблемного поля и код ошибки.
 */
@Getter
public class CredentialsValidationException extends RuntimeException {

    private final List<Violation> violations;

    public CredentialsValidationException(List<Violation> violations) {
        this.violations = List.copyOf(violations);
    }

    public record Violation(String field, String code) {
    }
}
