package io.github.returnaction.fitness.identity.registration.recovery;

public class RecoveryConflictException extends RuntimeException {
    private final String code;

    public RecoveryConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
