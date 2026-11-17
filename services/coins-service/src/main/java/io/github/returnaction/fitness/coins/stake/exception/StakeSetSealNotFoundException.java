package io.github.returnaction.fitness.coins.stake.exception;

public final class StakeSetSealNotFoundException extends RuntimeException {

    public StakeSetSealNotFoundException() {
        super("Seal operation not found.");
    }
}
