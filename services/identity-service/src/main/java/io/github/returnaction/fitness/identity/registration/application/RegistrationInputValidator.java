package io.github.returnaction.fitness.identity.registration.application;

import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Нормализует email и проверяет правила v1, не изменяя пароль. */
@Component
public class RegistrationInputValidator {

    public ValidatedRegistration validate(RegisterUserRequest request) {
        List<RegistrationValidationException.Violation> violations = new ArrayList<>();
        String email = canonicalEmail(request == null ? null : request.email(), violations);
        String password = request == null ? null : request.password();
        validatePassword(password, violations);

        if (!violations.isEmpty()) {
            throw new RegistrationValidationException(violations);
        }
        return new ValidatedRegistration(email, password);
    }

    private String canonicalEmail(String source, List<RegistrationValidationException.Violation> violations) {
        if (source == null) {
            violations.add(new RegistrationValidationException.Violation("email", "required"));
            return null;
        }
        String trimmed = source.strip();
        if (trimmed.length() < 3 || trimmed.length() > 254 || !hasValidEmailStructure(trimmed)) {
            violations.add(new RegistrationValidationException.Violation("email", "invalid_format"));
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private boolean hasValidEmailStructure(String email) {
        int separator = email.indexOf('@');
        return separator > 0
                && separator < email.length() - 1
                && separator == email.lastIndexOf('@')
                && email.chars().allMatch(character -> character >= '!' && character <= '~');
    }

    private void validatePassword(String password, List<RegistrationValidationException.Violation> violations) {
        if (password == null) {
            violations.add(new RegistrationValidationException.Violation("password", "required"));
            return;
        }
        int characterCount = password.codePointCount(0, password.length());
        if (characterCount < 1 || characterCount > 128 || password.getBytes(StandardCharsets.UTF_8).length > 1024) {
            violations.add(new RegistrationValidationException.Violation("password", "invalid_length"));
        }
    }

    public record ValidatedRegistration(String emailCanonical, String password) {}
}
