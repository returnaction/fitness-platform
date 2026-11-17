package io.github.returnaction.fitness.coins.stake.exception;

public final class StakeSetSettledException extends RuntimeException {

    public StakeSetSettledException() {
        super("Stake set is already settled.");
    }
}
