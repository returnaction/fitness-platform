package io.github.returnaction.fitness.coins.stake.exception;

public final class StakeSetCancellationConflictException extends RuntimeException {

    public StakeSetCancellationConflictException() {
        super("Stake set is not draining for this cancellation.");
    }
}
