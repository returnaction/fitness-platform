package io.github.returnaction.fitness.exercisecatalog.catalog.service;

import io.github.returnaction.fitness.exercisecatalog.catalog.dto.ExerciseCatalogResponse;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogCategoryEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogVariationEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.mapper.ExerciseCatalogMapper;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogCategoryRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogExerciseRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogVariationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExerciseCatalogService {

    private final CatalogCategoryRepository catalogCategoryRepository;
    private final CatalogExerciseRepository catalogExerciseRepository;
    private final CatalogVariationRepository catalogVariationRepository;
    private final ExerciseCatalogMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExerciseCatalogResponse getExercises() {

        List<CatalogCategoryEntity> catalog = catalogCategoryRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc();
        List<CatalogExerciseEntity> exercise = catalogExerciseRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc();
        List<CatalogVariationEntity> variations = catalogVariationRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc();

        // Собираем вариации каждого упражнения, сохраняя порядок из репозитория.
        Map<Short, List<CatalogVariationEntity>> variationsByExercise = variations.stream()
            .collect(Collectors.groupingBy(variation -> variation.getExercise().getId()));

        Map<Short, List<ExerciseCatalogResponse.Exercise>> exercisesByCategory = new HashMap<>();
        for (CatalogExerciseEntity entity : exercise) {
            List<CatalogVariationEntity> exerciseVariations =
                variationsByExercise.getOrDefault(entity.getId(), List.of());

            Short defaultVariationId = exerciseVariations.stream()
                .filter(variation -> "STANDARD".equals(variation.getCode()))
                .map(CatalogVariationEntity::getId)
                .findFirst()
                .orElse(null);

            // Без активного STANDARD упражнение недоступно для выбора.
            if (defaultVariationId == null) {
                continue;
            }

            List<ExerciseCatalogResponse.Variation> variationResponses = exerciseVariations.stream()
                .map(mapper::toVariation)
                .toList();
            ExerciseCatalogResponse.Exercise response = mapper.toExercise(entity, defaultVariationId, variationResponses);
            exercisesByCategory.computeIfAbsent(entity.getCategory().getId(), id -> new ArrayList<>())
                .add(response);
        }

        // В ответ попадают только активные категории и принадлежащие им упражнения.
        List<ExerciseCatalogResponse.Category> categories = catalog.stream()
            .map(category -> mapper.toCategory(
                category, exercisesByCategory.getOrDefault(category.getId(), List.of())))
            .toList();

        return new ExerciseCatalogResponse(categories);
    }
}
