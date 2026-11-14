package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

/** Публичное представление упражнения в списке; только активные записи. */
public record ExerciseListItem(
        int id,
        int categoryId,
        String code,
        String name,
        String description,
        String primaryMetric,
        int sortOrder) {
}
