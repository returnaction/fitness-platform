package io.github.returnaction.fitness.coins.wallet.exception;

/**
 * Означает нарушение инварианта: у активного пользователя отсутствует кошелёк.
 *
 * <p>Spec 017 T004.5: выбрасывается чтением кошелька и отображается
 * в {@code 500 wallet_invariant_violation}.</p>
 */
public final class WalletInvariantViolationException extends RuntimeException {
}
