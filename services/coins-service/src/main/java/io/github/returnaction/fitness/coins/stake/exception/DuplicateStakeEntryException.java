package io.github.returnaction.fitness.coins.stake.exception;

public final class DuplicateStakeEntryException extends RuntimeException {

    public DuplicateStakeEntryException() {
        super("Stake entry is duplicated across batches.");
    }
}
