package io.github.returnaction.fitness.identity.registration.application;

public class RegistrationConflictException extends RuntimeException {

    private final String code;

    public RegistrationConflictException(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
