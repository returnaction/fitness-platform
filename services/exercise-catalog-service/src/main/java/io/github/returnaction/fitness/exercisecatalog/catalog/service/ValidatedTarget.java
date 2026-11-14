package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import java.util.Objects;

/**
 * Подтверждённая сервером цель: метрика и единица взяты из каталога, а не из запроса.
 */
public record ValidatedTarget(
    long exerciseId,
    long variationId,
    PrimaryMetric primaryMetric,
    long targetValue) {

    public ValidatedTarget {
        Objects.requireNonNull(primaryMetric, "primaryMetric");
        if (targetValue <= 0) {
            throw new IllegalArgumentException("targetValue must be positive");
        }
    }

    public String unit() {
        return primaryMetric.unit();
    }
}
