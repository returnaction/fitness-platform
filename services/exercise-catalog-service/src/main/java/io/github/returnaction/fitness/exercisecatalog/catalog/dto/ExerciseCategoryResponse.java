package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

/** Публичное представление активной категории каталога. */
public record ExerciseCategoryResponse(
        int id,
        String code,
        String name,
        int sortOrder) {
}
