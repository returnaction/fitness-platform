package io.github.returnaction.fitness.coins.stake.exception;

public final class HoldChallengeMismatchException extends RuntimeException {

    public HoldChallengeMismatchException() {
        super("Hold belongs to another challenge.");
    }
}
