package io.github.returnaction.fitness.exercisecatalog.catalog.validation;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.MetricUnit;

/**
 * Доверенный снимок задания: собирается сервером из данных каталога,
 * тексты от телефона не принимаются (49-task-metric-contract.md).
 */
public record VariationSnapshot(
        int exerciseId,
        long exerciseVersion,
        String exerciseName,
        int variationId,
        long variationVersion,
        String variationName,
        String description,
        String metric,
        MetricUnit unit,
        long targetValue,
        String instructions) {
}
