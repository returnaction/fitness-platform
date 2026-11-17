package io.github.returnaction.fitness.challenge.coins;

import io.github.returnaction.fitness.challenge.shared.exception.BusinessConflictException;
import io.github.returnaction.fitness.contracts.coins.stake.v1.ReserveOutcome;
import java.util.function.Consumer;

/**
 * Единая обработка окончательных бизнес-отказов ReserveStake.
 */
public final class CoinsReserveRejection {

    private CoinsReserveRejection() {
    }

    public static void throwIfRejected(ReserveOutcome outcome, Consumer<String> failOperation) {
        if (outcome == ReserveOutcome.RESERVE_OUTCOME_INSUFFICIENT_FUNDS) {
            failOperation.accept("insufficient_coins");
            throw new BusinessConflictException("insufficient_coins", "Not enough coins.");
        }
        if (outcome == ReserveOutcome.RESERVE_OUTCOME_ACTIVE_HOLD_EXISTS) {
            failOperation.accept("stake_conflict");
            throw new BusinessConflictException("stake_conflict", "User already has an active stake.");
        }
    }
}
