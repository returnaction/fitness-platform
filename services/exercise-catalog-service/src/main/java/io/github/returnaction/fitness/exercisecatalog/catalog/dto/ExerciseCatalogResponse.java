package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogMetric;

import java.util.List;

public record ExerciseCatalogResponse(List<Category> categories) {

    public record Category(
        Short id,
        String code,
        String name,
        List<Exercise> exercises
    ) {
    }

    public record Exercise(
        Short id,
        String code,
        String name,
        String description,
        CatalogMetric metric,
        Short defaultVariationId,
        List<Variation> variations
    ) {
    }

    public record Variation(
        Short id,
        String code,
        String name,
        String description,
        String instructions,
        long minTarget,
        long maxTarget,
        long targetStep
    ) {
    }
}
