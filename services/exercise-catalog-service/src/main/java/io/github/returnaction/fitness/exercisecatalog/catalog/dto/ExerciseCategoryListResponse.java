package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

import java.util.List;

public record ExerciseCategoryListResponse(List<ExerciseCategoryResponse> items) {

    public ExerciseCategoryListResponse {
        items = List.copyOf(items);
    }
}
