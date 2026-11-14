package io.github.returnaction.fitness.exercisecatalog.catalog.exception;

/**
 * Недопустимый размер страницы.
 */
public final class InvalidPaginationException extends RuntimeException {

    private final int limit;

    public InvalidPaginationException(int limit) {
        super("Page size is outside the allowed range.");
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
