package io.github.returnaction.fitness.exercisecatalog.catalog.validation;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogCategoryEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogVariationEntity;

/**
 * Загруженная цепочка «вариация → упражнение → категория», разрешённая use case-ом.
 * Все элементы присутствуют; активность проверяет {@link CatalogTargetValidator}.
 */
public record ResolvedVariationChain(
        CatalogVariationEntity variation,
        CatalogExerciseEntity exercise,
        CatalogCategoryEntity category) {
}
