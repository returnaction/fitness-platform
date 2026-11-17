package io.github.returnaction.fitness.coins.stake.exception;

public final class HoldNotReleasableException extends RuntimeException {

    public HoldNotReleasableException() {
        super("Hold has already been released or settled.");
    }
}
