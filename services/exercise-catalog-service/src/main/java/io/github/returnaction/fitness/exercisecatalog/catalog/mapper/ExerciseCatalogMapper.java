package io.github.returnaction.fitness.exercisecatalog.catalog.mapper;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCatalogResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogCategoryEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogVariationEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Преобразует данные каталога в DTO ответа телефону.
 */
@Component
public class ExerciseCatalogMapper {

    public ExerciseCatalogResponse.Category toCategory(CatalogCategoryEntity entity,
                                                       List<ExerciseCatalogResponse.Exercise> exercises) {
        return new ExerciseCatalogResponse.Category(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            exercises
        );
    }

    public ExerciseCatalogResponse.Exercise toExercise(CatalogExerciseEntity entity,
                                                       Short defaultVariationId,
                                                       List<ExerciseCatalogResponse.Variation> variations) {
        return new ExerciseCatalogResponse.Exercise(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            entity.getDescription(),
            entity.getPrimaryMetric(),
            defaultVariationId,
            variations
        );
    }

    public ExerciseCatalogResponse.Variation toVariation(CatalogVariationEntity entity) {
        return new ExerciseCatalogResponse.Variation(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            entity.getDescription(),
            entity.getInstructions(),
            entity.getMinTarget(),
            entity.getMaxTarget(),
            entity.getTargetStep()
        );
    }
}
