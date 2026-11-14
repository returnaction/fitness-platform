package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

import java.util.List;

public record ExerciseListResponse(List<ExerciseListItem> items,
                                   String nextCursor) {

    public ExerciseListResponse {
        items = List.copyOf(items);
    }
}
