package io.github.returnaction.fitness.identity.session.exception;

public class AuthenticationFailureException extends RuntimeException {

    public enum Reason {
        INVALID_CREDENTIALS, ACCOUNT_UNAVAILABLE, REGISTRATION_PENDING, INVALID_REFRESH, REFRESH_ACCOUNT_UNAVAILABLE
    }

    private final Reason reason;

    public AuthenticationFailureException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
