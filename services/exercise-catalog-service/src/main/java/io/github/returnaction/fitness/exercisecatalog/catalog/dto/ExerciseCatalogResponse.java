package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogMetric;

import java.util.List;

public record ExerciseCatalogResponse(List<Category> categories) {

    public ExerciseCatalogResponse {
        categories = List.copyOf(categories);
    }

    public record Category(
        Short id,
        String code,
        String name,
        List<Exercise> exercises
    ) {
        public Category {
            exercises = List.copyOf(exercises);
        }
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
        public Exercise {
            variations = List.copyOf(variations);
        }
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
