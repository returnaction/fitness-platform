package io.github.returnaction.fitness.accounts.profile.exception;

/**
 * Профилю не удалось выделить уникальные значения за ограниченное число
 * попыток.
 */
public final class ProfileCreationExhaustedException extends RuntimeException {

    public ProfileCreationExhaustedException() {
        super("Could not allocate unique profile values");
    }
}
