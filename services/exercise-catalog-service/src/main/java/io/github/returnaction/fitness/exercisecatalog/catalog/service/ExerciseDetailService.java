package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseDetailResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseVariationResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogCategoryEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.exception.ExerciseNotFoundException;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogCategoryRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogExerciseRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogVariationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExerciseDetailService {

    private final CatalogExerciseRepository exercises;
    private final CatalogCategoryRepository categories;
    private final CatalogVariationRepository variations;

    @Transactional(readOnly = true)
    public ExerciseDetailResponse get(Short exerciseId) {
        CatalogExerciseEntity exercise = exercises.findById(exerciseId)
            .filter(CatalogExerciseEntity::isActive)
            .orElseThrow(ExerciseNotFoundException::new);
        CatalogCategoryEntity category = categories.findById(exercise.getCategoryId())
            .filter(CatalogCategoryEntity::isActive)
            .orElseThrow(ExerciseNotFoundException::new);

        var activeVariations = variations
            .findByExerciseIdAndIsActiveTrueOrderBySortOrderAscIdAsc(exerciseId)
            .stream()
            .map(variation -> new ExerciseVariationResponse(variation.getId(), variation.getCode(),
                variation.getName(), variation.getDescription(), variation.getInstructions(),
                variation.getMinTarget(), variation.getMaxTarget(), variation.getTargetStep()))
            .toList();

        return new ExerciseDetailResponse(exercise.getId(), exercise.getCode(), exercise.getName(),
            exercise.getDescription(),
            new ExerciseDetailResponse.Category(category.getId(), category.getCode(), category.getName()),
            exercise.getPrimaryMetric(), exercise.getPrimaryMetric().unit(), activeVariations,
            exercise.getVersion());
    }
}
