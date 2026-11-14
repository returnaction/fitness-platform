package io.github.returnaction.fitness.exercisecatalog.catalog.service;

/**
 * Разобранный cursor: последний ключ страницы {@code (sort_order, id)} и фильтр категории.
 */
public record ExerciseCursor(Integer categoryId, int sortOrder, int id) {
}
