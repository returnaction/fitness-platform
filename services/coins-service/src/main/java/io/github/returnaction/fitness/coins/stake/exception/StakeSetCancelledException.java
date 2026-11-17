package io.github.returnaction.fitness.coins.stake.exception;

public final class StakeSetCancelledException extends RuntimeException {

    public StakeSetCancelledException() {
        super("Stake set is cancelled or draining.");
    }
}
