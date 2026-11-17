package io.github.returnaction.fitness.coins.stake.exception;

public final class OperationNotFoundException extends RuntimeException {

    public OperationNotFoundException() {
        super("Operation not found.");
    }
}
