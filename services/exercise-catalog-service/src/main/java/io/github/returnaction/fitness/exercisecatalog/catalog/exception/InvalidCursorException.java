package io.github.returnaction.fitness.exercisecatalog.catalog.exception;

/**
 * Cursor не декодируется, имеет неизвестную версию или не соответствует фильтру.
 */
public final class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super("Cursor is invalid.");
    }
}
