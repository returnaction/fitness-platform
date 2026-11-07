package io.github.returnaction.fitness.identity.registration.web;

public class RegistrationRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public RegistrationRateLimitException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
