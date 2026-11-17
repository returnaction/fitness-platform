package io.github.returnaction.fitness.challenge.coins.exception;

/**
 * Тот же operation ID использован с другим payload; повтор не поможет.
 */
public final class CoinsOperationConflictException extends RuntimeException {

    public CoinsOperationConflictException(Throwable cause) {
        super("Coins operation ID conflicts with previous request.", cause);
    }
}
