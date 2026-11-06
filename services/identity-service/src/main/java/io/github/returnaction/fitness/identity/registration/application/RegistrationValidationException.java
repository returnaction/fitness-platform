package io.github.returnaction.fitness.identity.registration.application;

import java.util.List;

public class RegistrationValidationException extends RuntimeException {

    private final List<Violation> violations;

    public RegistrationValidationException(List<Violation> violations) {
        this.violations = List.copyOf(violations);
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public record Violation(String field, String code) {}
}
