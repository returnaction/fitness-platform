package io.github.returnaction.fitness.exercisecatalog.catalog.validation;

import io.github.returnaction.fitness.exercisecatalog.catalog.domain.PrimaryMetric;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogExerciseEntity;
import io.github.returnaction.fitness.exercisecatalog.catalog.entity.CatalogVariationEntity;
import org.springframework.stereotype.Component;

/**
 * Чистая бизнес-проверка цели: активная цепочка, метрика, диапазон и шаг.
 * Порядок исходов: INACTIVE → TARGET_OUT_OF_RANGE → TARGET_STEP_INVALID.
 */
@Component
public class CatalogTargetValidator {

    public TargetValidationResult validate(ResolvedVariationChain chain, long targetValue) {
        if (!isActiveChain(chain)) {
            return TargetValidationResult.failure(ChallengeTargetStatus.INACTIVE);
        }
        CatalogVariationEntity variation = chain.variation();
        if (targetValue <= 0 || targetValue < variation.getMinTarget() || targetValue > variation.getMaxTarget()) {
            return TargetValidationResult.failure(ChallengeTargetStatus.TARGET_OUT_OF_RANGE);
        }
        if (targetValue % variation.getTargetStep() != 0) {
            return TargetValidationResult.failure(ChallengeTargetStatus.TARGET_STEP_INVALID);
        }
        return TargetValidationResult.ok(toSnapshot(chain, targetValue));
    }

    private boolean isActiveChain(ResolvedVariationChain chain) {
        return chain.variation().getIsActive()
                && chain.exercise().getIsActive()
                && chain.category().getIsActive();
    }

    private VariationSnapshot toSnapshot(ResolvedVariationChain chain, long targetValue) {
        CatalogVariationEntity variation = chain.variation();
        CatalogExerciseEntity exercise = chain.exercise();
        PrimaryMetric metric = exercise.getPrimaryMetric();
        return new VariationSnapshot(
                exercise.getId(),
                exercise.getVersion(),
                exercise.getName(),
                variation.getId(),
                variation.getVersion(),
                variation.getName(),
                variation.getDescription(),
                metric.name(),
                metric.unit(),
                targetValue,
                variation.getInstructions());
    }
}
