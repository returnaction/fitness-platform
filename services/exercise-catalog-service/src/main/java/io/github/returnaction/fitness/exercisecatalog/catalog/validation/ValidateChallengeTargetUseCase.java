package io.github.returnaction.fitness.exercisecatalog.catalog.validation;

import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogCategoryRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogExerciseRepository;
import io.github.returnaction.fitness.exercisecatalog.catalog.repository.CatalogVariationRepository;
import org.springframework.stereotype.Service;

/**
 * Единый use case проверки цели: загрузка активной цепочки из БД и проверка
 * метрики, диапазона и шага. Используется будущим gRPC endpoint-ом; REST-чтение
 * каталога его не использует.
 */
@Service
public class ValidateChallengeTargetUseCase {

    private final CatalogVariationRepository variations;
    private final CatalogExerciseRepository exercises;
    private final CatalogCategoryRepository categories;
    private final CatalogTargetValidator validator;

    public ValidateChallengeTargetUseCase(
            CatalogVariationRepository variations,
            CatalogExerciseRepository exercises,
            CatalogCategoryRepository categories,
            CatalogTargetValidator validator) {
        this.variations = variations;
        this.exercises = exercises;
        this.categories = categories;
        this.validator = validator;
    }

    /**
     * Проверяет пару «упражнение/вариация» и целевое значение. Чужая пара ID —
     * {@link ChallengeTargetStatus#NOT_FOUND}: клиент не должен узнавать,
     * к какому упражнению относится чужая вариация.
     */
    public TargetValidationResult check(int exerciseId, int variationId, long targetValue) {
        return variations.findById(variationId)
                .filter(variation -> variation.getExerciseId() == exerciseId)
                .flatMap(variation -> exercises.findById(exerciseId)
                        .map(exercise -> categories.findById(exercise.getCategoryId())
                                .map(category -> validator.validate(
                                        new ResolvedVariationChain(variation, exercise, category), targetValue))
                                .orElse(TargetValidationResult.failure(ChallengeTargetStatus.NOT_FOUND))))
                .orElse(TargetValidationResult.failure(ChallengeTargetStatus.NOT_FOUND));
    }
}
