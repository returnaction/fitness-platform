package io.github.returnaction.fitness.exercisecatalog.catalog.exception;

/**
 * Цель меньше минимума или больше максимума вариации.
 */
public final class TargetOutOfRangeException extends CatalogTargetException {

    private final long targetValue;
    private final long minTarget;
    private final long maxTarget;

    public TargetOutOfRangeException(long exerciseId, long variationId, long targetValue,
        long minTarget, long maxTarget) {
        super("Target value is outside the allowed range.", exerciseId, variationId);
        this.targetValue = targetValue;
        this.minTarget = minTarget;
        this.maxTarget = maxTarget;
    }

    public long getTargetValue() {
        return targetValue;
    }

    public long getMinTarget() {
        return minTarget;
    }

    public long getMaxTarget() {
        return maxTarget;
    }
}
