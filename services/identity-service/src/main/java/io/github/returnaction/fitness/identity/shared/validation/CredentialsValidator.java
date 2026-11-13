package io.github.returnaction.fitness.identity.shared.validation;

import io.github.returnaction.fitness.identity.shared.model.ValidatedCredentials;
import io.github.returnaction.fitness.identity.shared.exception.CredentialsValidationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Нормализует email и проверяет правила v1, не изменяя пароль. */
@Component
public class CredentialsValidator {

    public ValidatedCredentials validate(String email, String password) {

        List<CredentialsValidationException.Violation> violations = new ArrayList<>();
        String canonicalEmail = canonicalEmail(email, violations);
        validatePassword(password, violations);

        if (!violations.isEmpty()) {
            throw new CredentialsValidationException(violations);
        }
        return new ValidatedCredentials(canonicalEmail, password);
    }

    /** Использовать после проверки DTO на входе HTTP. */
    public ValidatedCredentials normalize(String email, String password) {

        return new ValidatedCredentials(email.strip().toLowerCase(Locale.ROOT), password);
    }

    private String canonicalEmail(String source, List<CredentialsValidationException.Violation> violations) {
        if (source == null) {
            violations.add(new CredentialsValidationException.Violation("email", "required"));
            return null;
        }
        String trimmed = source.strip();
        if (trimmed.length() < 3 || trimmed.length() > 254 || !hasValidEmailStructure(trimmed)) {
            violations.add(new CredentialsValidationException.Violation("email", "invalid_format"));
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private boolean hasValidEmailStructure(String email) {

        int separator = email.indexOf('@');
        return separator > 0 && separator < email.length() - 1 && separator == email.lastIndexOf('@')
                && email.chars().allMatch(character -> character >= '!' && character <= '~');
    }

    private void validatePassword(String password, List<CredentialsValidationException.Violation> violations) {
        if (password == null) {
            violations.add(new CredentialsValidationException.Violation("password", "required"));
            return;
        }
        int characterCount = password.codePointCount(0, password.length());
        if (characterCount < 1 || characterCount > 128 || password.getBytes(StandardCharsets.UTF_8).length > 1024) {
            violations.add(new CredentialsValidationException.Violation("password", "invalid_length"));
        }
    }
}
