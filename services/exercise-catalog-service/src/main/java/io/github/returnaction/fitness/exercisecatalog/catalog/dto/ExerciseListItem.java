package io.github.returnaction.fitness.exercisecatalog.catalog.dto;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;

public record ExerciseListItem(int id,
                               String code,
                               String name,
                               PrimaryMetric primaryMetric) {
}
