package io.github.returnaction.fitness.challenge.coins.exception;

/**
 * Результат вызова Coins неизвестен: UNAVAILABLE или DEADLINE_EXCEEDED.
 * Операция не считается отказом и повторяется с тем же operation ID.
 */
public final class CoinsOutcomeUnknownException extends RuntimeException {

    public CoinsOutcomeUnknownException(Throwable cause) {
        super("Coins outcome is unknown.", cause);
    }
}
