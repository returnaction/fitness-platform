package io.github.returnaction.fitness.gateway.security.session;

public final class SessionVerificationUnavailableException extends RuntimeException {

    public SessionVerificationUnavailableException(String message) {
        super(message);
    }

    public SessionVerificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
