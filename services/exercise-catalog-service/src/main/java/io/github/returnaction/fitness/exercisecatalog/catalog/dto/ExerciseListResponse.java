package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

import java.util.List;

/** Страница списка упражнений: элементы и курсор следующей страницы или null. */
public record ExerciseListResponse(
        List<ExerciseListItem> items,
        String nextCursor) {
}
