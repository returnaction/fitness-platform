package io.github.returnaction.fitness.coins.stake.exception;

/**
 * Тот же operation ID использован с другим payload.
 */
public final class OperationIdConflictException extends RuntimeException {

    public OperationIdConflictException() {
        super("Operation ID conflicts with previous request.");
    }
}
