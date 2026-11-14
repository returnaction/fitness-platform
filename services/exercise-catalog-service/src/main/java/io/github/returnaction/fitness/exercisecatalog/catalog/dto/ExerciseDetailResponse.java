package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import java.util.List;

public record ExerciseDetailResponse(int id,
                                     String code,
                                     String name,
                                     String description,
                                     Category category,
                                     PrimaryMetric primaryMetric,
                                     String unit,
                                     List<ExerciseVariationResponse> variations,
                                     long version) {

    public ExerciseDetailResponse {
        variations = List.copyOf(variations);
    }

    public record Category(int id, String code, String name) {
    }
}
