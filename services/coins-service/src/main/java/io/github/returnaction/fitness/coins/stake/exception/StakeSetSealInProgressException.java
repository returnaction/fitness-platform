package io.github.returnaction.fitness.coins.stake.exception;

public final class StakeSetSealInProgressException extends RuntimeException {

    public StakeSetSealInProgressException() {
        super("Another stake set seal is in progress.");
    }
}
