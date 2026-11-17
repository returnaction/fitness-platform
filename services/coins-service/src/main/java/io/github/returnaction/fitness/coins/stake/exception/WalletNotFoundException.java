package io.github.returnaction.fitness.coins.stake.exception;

/**
 * У пользователя нет кошелька; gRPC-маппинг выполняется на уровне транспорта.
 */
public final class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException() {
        super("Wallet not found.");
    }
}
