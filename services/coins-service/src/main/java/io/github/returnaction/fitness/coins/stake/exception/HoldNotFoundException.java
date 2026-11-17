package io.github.returnaction.fitness.coins.stake.exception;

public final class HoldNotFoundException extends RuntimeException {

    public HoldNotFoundException() {
        super("Hold not found.");
    }
}
