package io.github.returnaction.fitness.identity.session.validation;

import io.github.returnaction.fitness.identity.session.dto.LoginRequest;
import io.github.returnaction.fitness.identity.shared.exception.CredentialsValidationException;
import io.github.returnaction.fitness.identity.shared.validation.CredentialsValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Проверяет правила email и пароля, которые не выражаются стандартными
 * аннотациями.
 */
public class LoginCredentialsConstraintValidator implements ConstraintValidator<ValidLoginCredentials, LoginRequest> {

    private final CredentialsValidator validator;

    public LoginCredentialsConstraintValidator(CredentialsValidator validator) {
        this.validator = validator;
    }

    @Override
    public boolean isValid(LoginRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        try {
            validator.validate(request.email(), request.password());
            return true;
        } catch (CredentialsValidationException exception) {
            context.disableDefaultConstraintViolation();
            boolean invalid = false;
            for (var violation : exception.getViolations()) {
                if ("required".equals(violation.code())) {
                    continue; // Отсутствующие поля проверяются через @NotNull.
                }
                context.buildConstraintViolationWithTemplate(violation.code())
                        .addPropertyNode(violation.field())
                        .addConstraintViolation();
                invalid = true;
            }
            return !invalid;
        }
    }
}
