package io.github.returnaction.fitness.identity.registration.model;

public record ValidatedRegistration(String emailCanonical, String password) {
    @Override
    public String toString() {
        return "ValidatedRegistration[emailCanonical=<redacted>, password=<redacted>]";
    }
}
