package io.github.returnaction.fitness.coins.wallet.exception;

/**
 * Временная ошибка БД или транзакции, из-за которой баланс сейчас недоступен.
 *
 * <p>Spec 017 T004.5: оборачивает временные {@code DataAccessException}
 * и отображается в {@code 503 coins_unavailable}.</p>
 */
public final class CoinsUnavailableException extends RuntimeException {

    public CoinsUnavailableException(Throwable cause) {
        super(cause);
    }
}
