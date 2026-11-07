package io.github.returnaction.fitness.identity.registration.recovery;

public class RecoveryAccessException extends RuntimeException {
    private final String code;

    public RecoveryAccessException(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
