package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseDetailResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseVariationResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogExerciseRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogVariationRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Карточка упражнения: метрика, единица и активные вариации с инструкцией
 * и пределами. Неизвестное или выключенное упражнение — 404 not_found.
 */
@Service
public class ExerciseDetailService {

    private final CatalogExerciseRepository exercises;
    private final CatalogVariationRepository variations;

    public ExerciseDetailService(CatalogExerciseRepository exercises, CatalogVariationRepository variations) {
        this.exercises = exercises;
        this.variations = variations;
    }

    @Transactional(readOnly = true)
    public ExerciseDetailResponse detail(int id) {
        var exercise = exercises.findById(id)
                .filter(CatalogExerciseEntity::getIsActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found"));

        List<ExerciseVariationResponse> activeVariations = variations
                .findByExerciseIdAndIsActiveTrueOrderBySortOrderAscIdAsc(id).stream()
                .map(variation -> new ExerciseVariationResponse(
                        variation.getId(),
                        variation.getCode(),
                        variation.getName(),
                        variation.getDescription(),
                        variation.getInstructions(),
                        variation.getMinTarget(),
                        variation.getMaxTarget(),
                        variation.getTargetStep(),
                        variation.getSortOrder()))
                .toList();

        return new ExerciseDetailResponse(
                exercise.getId(),
                exercise.getCategoryId(),
                exercise.getCode(),
                exercise.getName(),
                exercise.getDescription(),
                exercise.getPrimaryMetric().name(),
                exercise.getPrimaryMetric().unit().name(),
                exercise.getSortOrder(),
                activeVariations);
    }
}
