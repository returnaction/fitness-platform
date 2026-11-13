package io.github.returnaction.fitness.exercisecatalog.catalog.validation;

import java.util.Optional;

/** Результат единой проверки цели: статус и снимок при {@link ChallengeTargetStatus#OK}. */
public record TargetValidationResult(ChallengeTargetStatus status, VariationSnapshot snapshot) {

    public static TargetValidationResult ok(VariationSnapshot snapshot) {
        return new TargetValidationResult(ChallengeTargetStatus.OK, snapshot);
    }

    public static TargetValidationResult failure(ChallengeTargetStatus status) {
        return new TargetValidationResult(status, null);
    }
}
