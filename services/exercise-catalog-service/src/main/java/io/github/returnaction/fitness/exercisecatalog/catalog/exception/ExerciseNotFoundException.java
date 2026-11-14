package io.github.returnaction.fitness.exercisecatalog.catalog.exception;

/**
 * Упражнение неизвестно, выключено или его категория выключена.
 */
public final class ExerciseNotFoundException extends RuntimeException {

    public ExerciseNotFoundException() {
        super("Exercise not found.");
    }
}
