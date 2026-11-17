package io.github.returnaction.fitness.coins.stake.exception;

public final class ActiveHoldsRemainException extends RuntimeException {

    public ActiveHoldsRemainException() {
        super("Active holds remain in the stake set.");
    }
}
