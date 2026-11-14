package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

import java.util.List;

/** Карточка упражнения: метрика с единицей и её активные вариации. */
public record ExerciseDetailResponse(
        int id,
        int categoryId,
        String code,
        String name,
        String description,
        String primaryMetric,
        String unit,
        int sortOrder,
        List<ExerciseVariationResponse> variations) {
}
