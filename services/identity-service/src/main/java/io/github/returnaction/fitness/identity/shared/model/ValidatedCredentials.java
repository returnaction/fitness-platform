package io.github.returnaction.fitness.identity.shared.model;

public record ValidatedCredentials(String emailCanonical,
                                   String password) {

    @Override
    public String toString() {
        return "ValidatedCredentials[emailCanonical=<redacted>, password=<redacted>]";
    }
}
