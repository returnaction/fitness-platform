package io.github.returnaction.fitness.exercisecatalog.catalog.validation;

/**
 * Итог проверки цели: имена исходов согласованы с межсервисным контрактом
 * Catalog ↔ Challenge (57-exercise-catalog-service.md).
 */
public enum ChallengeTargetStatus {
    OK,
    NOT_FOUND,
    INACTIVE,
    TARGET_OUT_OF_RANGE,
    TARGET_STEP_INVALID
}
