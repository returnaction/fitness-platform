package io.github.returnaction.fitness.coins.stake.exception;

public final class StakeSetIncompleteException extends RuntimeException {

    public StakeSetIncompleteException() {
        super("Stake set count or digest does not match uploaded batches.");
    }
}
