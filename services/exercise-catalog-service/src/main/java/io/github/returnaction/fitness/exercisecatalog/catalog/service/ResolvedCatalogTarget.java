package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import java.util.Objects;

/**
 * Загруженная из каталога цепочка категория → упражнение → вариация.
 *
 * <p>Записи не фильтруются по активности при загрузке: решение об отказе
 * принимает {@link CatalogTargetValidator}, а не слой чтения.
 */
public record ResolvedCatalogTarget(
    long exerciseId,
    long variationId,
    boolean categoryActive,
    boolean exerciseActive,
    boolean variationActive,
    PrimaryMetric primaryMetric,
    long minTarget,
    long maxTarget,
    long targetStep) {

    public ResolvedCatalogTarget {
        Objects.requireNonNull(primaryMetric, "primaryMetric");
        if (minTarget <= 0) {
            throw new IllegalArgumentException("minTarget must be positive");
        }
        if (maxTarget < minTarget) {
            throw new IllegalArgumentException("maxTarget must be greater than or equal to minTarget");
        }
        if (targetStep <= 0) {
            throw new IllegalArgumentException("targetStep must be positive");
        }
    }
}
