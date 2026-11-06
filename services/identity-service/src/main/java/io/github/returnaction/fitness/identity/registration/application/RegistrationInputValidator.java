package io.github.returnaction.fitness.identity.registration.application;

import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Нормализует email и проверяет правила v1, не изменяя пароль. */
@Component
public class RegistrationInputValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[!-~]+@[!-~]+$");

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
        String email = source.strip().toLowerCase(Locale.ROOT);
        if (email.length() < 3
                || email.length() > 254
                || !EMAIL_PATTERN.matcher(email).matches()) {
            violations.add(new RegistrationValidationException.Violation("email", "invalid_format"));
        }
        return email;
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
