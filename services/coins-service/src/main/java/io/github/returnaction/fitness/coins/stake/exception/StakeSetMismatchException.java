package io.github.returnaction.fitness.coins.stake.exception;

public final class StakeSetMismatchException extends RuntimeException {

    public StakeSetMismatchException() {
        super("Stake set does not match active holds.");
    }
}
