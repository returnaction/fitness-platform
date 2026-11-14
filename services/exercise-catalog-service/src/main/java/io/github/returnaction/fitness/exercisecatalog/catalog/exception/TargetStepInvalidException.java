package io.github.returnaction.fitness.exercisecatalog.catalog.exception;

/**
 * Цель не кратна шагу вариации.
 */
public final class TargetStepInvalidException extends CatalogTargetException {

    private final long targetValue;
    private final long targetStep;

    public TargetStepInvalidException(long exerciseId, long variationId, long targetValue, long targetStep) {
        super("Target value does not match the allowed step.", exerciseId, variationId);
        this.targetValue = targetValue;
        this.targetStep = targetStep;
    }

    public long getTargetValue() {
        return targetValue;
    }

    public long getTargetStep() {
        return targetStep;
    }
}
