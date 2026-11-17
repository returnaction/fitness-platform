package io.github.returnaction.fitness.coins.stake.exception;

public final class StakeSetSealedException extends RuntimeException {

    public StakeSetSealedException() {
        super("Stake set is already sealed.");
    }
}
